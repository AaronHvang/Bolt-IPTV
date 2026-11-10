package io.opentv.live.data

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * 极简局域网收单服务。
 *
 * 电视上展示二维码，手机扫码后打开本服务提供的网页，在手机上粘贴订阅地址提交，
 * 电视端即时收到并保存，省去在遥控器上敲一长串 URL。
 */
class SourceWebServer(
    private val onAddUrl: (name: String, url: String, epg: String) -> Unit,
    private val onAddFile: (name: String, content: String) -> Boolean
) {

    private var serverSocket: ServerSocket? = null

    var port: Int = 0
        private set

    /** 二维码里要放的地址，例如 http://192.168.1.213:42001 */
    val address: String get() = "http://${lanIp()}:$port"

    fun start(): Boolean {
        if (serverSocket != null) return true
        return try {
            val ss = ServerSocket(0)
            serverSocket = ss
            port = ss.localPort
            thread(isDaemon = true, name = "SourceWebServer") {
                while (true) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(isDaemon = true) {
                        try {
                            handle(socket)
                        } catch (e: Exception) {
                            Log.w(TAG, "处理请求失败", e)
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "启动失败", e)
            stop()
            false
        }
    }

    fun stop() {
        val ss = serverSocket ?: return
        serverSocket = null
        runCatching { ss.close() }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 8000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        when {
            method == "GET" && (path == "/" || path == "/index.html") -> respond(socket, PAGE)
            method == "POST" && path == "/add" -> {
                if (contentLength > MAX_BODY) {
                    respond(socket, resultPage(false, "文件太大，请换一个小一点的播放列表"))
                    return
                }
                val form = parseForm(readBody(input, contentLength))
                val content = form["content"].orEmpty()
                val name = form["name"].orEmpty().trim()

                if (content.isNotBlank()) {
                    val ok = onAddFile(name, content)
                    respond(
                        socket,
                        if (ok) resultPage(true, "文件已保存到电视，回去刷新一下就能看了")
                        else resultPage(false, "文件保存失败，请重新上传")
                    )
                    return
                }

                val url = form["url"].orEmpty().trim()
                if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://"))) {
                    respond(socket, resultPage(false, "请填写 http:// 或 https:// 开头的订阅地址，或选择一个 m3u / txt 文件"))
                } else {
                    onAddUrl(name, url, form["epg"].orEmpty().trim())
                    respond(socket, resultPage(true, "已添加到电视，可以回到电视上刷新频道了"))
                }
            }
            else -> respond(socket, "<h3>404</h3>", status = "404 Not Found")
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        body.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun respond(socket: Socket, html: String, status: String = "200 OK") {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    /** 取设备在局域网里的 IPv4，优先站点本地地址 */
    private fun lanIp(): String {
        return try {
            val candidates = ArrayList<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        candidates.add(addr.hostAddress ?: continue)
                    }
                }
            }
            candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("10.") }
                ?: candidates.firstOrNull { it.startsWith("172.") }
                ?: candidates.firstOrNull()
                ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private companion object {
        const val TAG = "SourceWebServer"

        /** 上传的播放列表最大 16MB，防止异常请求把内存打满 */
        const val MAX_BODY = 16 * 1024 * 1024

        val PAGE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>添加播放源</title>
<style>
*{box-sizing:border-box}
body{margin:0;padding:20px;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#0f1115;color:#e8eaef}
h1{font-size:20px;margin:4px 0 6px}
p.tip{font-size:13px;color:#8b93a7;margin:0 0 18px;line-height:1.6}
label{display:block;font-size:13px;color:#a9b1c3;margin:16px 0 6px}
input{width:100%;padding:13px;font-size:15px;border-radius:10px;border:1px solid #2b3040;background:#171a21;color:#e8eaef;outline:none}
input:focus{border-color:#3d7eff}
button{width:100%;margin-top:22px;padding:15px;font-size:16px;font-weight:600;border:0;border-radius:10px;background:#3d7eff;color:#fff}
button:active{background:#2f63cc}
.hint{margin-top:16px;font-size:12px;color:#6d7488;line-height:1.7}
.picked{margin-top:8px;font-size:12px;color:#3ad07a;min-height:16px}
input[type=file]{padding:11px;font-size:13px;color:#a9b1c3}
</style>
</head>
<body>
<h1>添加电视播放源</h1>
<p class="tip">粘贴 m3u 订阅地址，或直接从手机选一个 m3u / txt 文件上传，电视会自动收下。</p>
<form id="f" method="post" action="/add">
<label>名称（可留空）</label>
<input name="name" id="name" placeholder="例如：家里宽带IPTV" autocomplete="off">
<label>订阅地址</label>
<input name="url" id="url" placeholder="http://example.com/playlist.m3u" autocomplete="off">
<label>或上传本地 m3u / txt 文件</label>
<input type="file" id="file" accept=".m3u,.m3u8,.txt,text/plain">
<div class="picked" id="picked"></div>
<input type="hidden" name="content" id="content">
<label>节目单地址（可留空）</label>
<input name="epg" placeholder="http://example.com/epg.xml.gz" autocomplete="off">
<button type="submit">提交到电视</button>
</form>
<div class="hint">手机和电视需要连在同一个 Wi-Fi / 局域网。<br>提交后电视上会自动出现这个源，直接返回电视即可。</div>
<script>
var fileEl=document.getElementById('file'),nameEl=document.getElementById('name'),
    urlEl=document.getElementById('url'),contentEl=document.getElementById('content'),
    pickedEl=document.getElementById('picked');
fileEl.addEventListener('change',function(){
  var f=fileEl.files[0];
  if(!f){contentEl.value='';pickedEl.textContent='';return;}
  var reader=new FileReader();
  reader.onload=function(){
    var buf=new Uint8Array(reader.result);
    var text=new TextDecoder('utf-8').decode(buf);
    // 国内很多 txt 播放列表是 GBK 编码，解出替换符就换 GBK 再试一次
    if(text.indexOf('\uFFFD')>=0){
      try{text=new TextDecoder('gbk').decode(buf);}catch(e){}
    }
    contentEl.value=text;
    if(!nameEl.value){nameEl.value=f.name.replace(/\.(m3u8?|txt)$/i,'');}
    pickedEl.textContent='已选择：'+f.name+'（'+(buf.length/1024).toFixed(1)+' KB）';
  };
  reader.readAsArrayBuffer(f);
});
document.getElementById('f').addEventListener('submit',function(e){
  if(!contentEl.value&&!urlEl.value.trim()){
    e.preventDefault();
    alert('请填写订阅地址，或选择一个 m3u / txt 文件');
  }
});
</script>
</body>
</html>
""".trimIndent()

        fun resultPage(ok: Boolean, message: String): String {
            val color = if (ok) "#3ad07a" else "#ff6b6b"
            val title = if (ok) "添加成功" else "添加失败"
            return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
body{margin:0;padding:40px 20px;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#0f1115;color:#e8eaef;text-align:center}
.dot{width:64px;height:64px;line-height:64px;font-size:34px;border-radius:50%;background:$color;color:#fff;margin:0 auto 20px}
h1{font-size:20px;margin:0 0 12px}
p{font-size:14px;color:#8b93a7;line-height:1.7}
a{color:#3d7eff;text-decoration:none;font-size:14px}
</style>
</head>
<body>
<div class="dot">${if (ok) "✓" else "!"}</div>
<h1>$title</h1>
<p>$message</p>
<p><a href="/">再添加一个</a></p>
</body>
</html>
""".trimIndent()
        }
    }
}
