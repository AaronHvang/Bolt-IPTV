package io.opentv.live.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.opentv.live.BuildConfig
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.InputStream

/**
 * 设备上的系统解码器只认 audio/mpeg（MP3），而 IPTV 组播流里的音频通常是
 * MPEG-1/2 Layer II（mp2），系统没有对应解码器。这里用 JLayer（纯 Java，
 * 支持 Layer I/II/III）实现一个 ExoPlayer 扩展音频渲染器，只接管 Layer I/II。
 */
@UnstableApi
class Mp2AudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink
) : DecoderAudioRenderer<Mp2Decoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "Mp2AudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        val mime = format.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (mime != MimeTypes.AUDIO_MPEG_L1 && mime != MimeTypes.AUDIO_MPEG_L2) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else 2
        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 48000
        return if (sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_16BIT, channels, sampleRate))) {
            C.FORMAT_HANDLED
        } else {
            C.FORMAT_UNSUPPORTED_SUBTYPE
        }
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Mp2Decoder {
        val initialSize = if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else DEFAULT_INPUT_SIZE
        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else 2
        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 48000
        return Mp2Decoder(initialSize, channels, sampleRate)
    }

    override fun getOutputFormat(decoder: Mp2Decoder): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(decoder.outputChannels)
            .setSampleRate(decoder.outputSampleRate)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

    private companion object {
        const val DEFAULT_INPUT_SIZE = 2048
    }
}

@UnstableApi
class Mp2DecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

/**
 * JLayer 解码链是“有状态”的：Decoder 首次 decodeFrame 时创建的 Layer II 解码器会把
 * 当时的 Bitstream、SynthesisFilter（多相合成滤波器）和 SampleBuffer 绑死，之后既换不了
 * 输出缓冲，也不能换输入流。多相合成滤波器的重叠窗必须跨帧延续，逐帧新建 Decoder 等于
 * 每 24ms 把滤波器状态清零，听起来就是持续的“沙沙/嗡嗡”失真。
 *
 * 因此这里维持一条持久链：持久 Bitstream + 持久 Decoder + 同一个 SampleBuffer，
 * 每帧只投喂“恰好一帧”的字节，避免 JLayer 在数据不足时补零导致字节错位。
 */
@UnstableApi
class Mp2Decoder(
    initialInputBufferSize: Int,
    defaultChannels: Int,
    defaultSampleRate: Int
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, Mp2DecoderException>(
    placeholderInputBuffers(NUM_BUFFERS), placeholderOutputBuffers(NUM_BUFFERS)
) {

    /** 解码出的音频声道数，用于上报输出格式 */
    var outputChannels: Int = defaultChannels
        private set

    /** 解码出的音频采样率，用于上报输出格式 */
    var outputSampleRate: Int = defaultSampleRate
        private set

    /** 从 ExoPlayer 收到的、还没解析成完整帧的原始字节 */
    private val pending = ByteQueue()

    /** 交给 JLayer 的输入；每次只放一整帧，读完必然为空 */
    private val feed = ByteQueue()

    private var bitstream: Bitstream? = null
    private var jlDecoder: Decoder? = null
    private var sampleBuffer: SampleBuffer? = null
    private var chainRate = 0
    private var chainChannels = 0

    private var failedFrames = 0
    private var loggedRate = 0
    private var loggedChannels = 0
    /** 当前自动增益系数，跨帧平滑变化 */
    private var gain = 1.0f

    init {
        setInitialInputBufferSize(initialInputBufferSize)
    }

    override fun getName(): String = "Mp2Decoder"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { buffer -> releaseOutputBuffer(buffer) }

    override fun createUnexpectedDecodeException(error: Throwable): Mp2DecoderException =
        Mp2DecoderException("MP2 解码失败", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean
    ): Mp2DecoderException? {
        if (reset) {
            // 换台/刷新后上一个流的残留字节与滤波历史都不再需要
            pending.clear()
            feed.clear()
            bitstream = null
            gain = 1.0f
        }
        val data = inputBuffer.data
        if (data == null || data.remaining() <= 0) {
            skip(outputBuffer, inputBuffer.timeUs)
            return null
        }
        val chunk = ByteArray(data.remaining())
        data.duplicate().get(chunk)
        pending.push(chunk, 0, chunk.size)
        Mp2DebugDump.writeInput(chunk)

        val pcmFrames = ArrayList<ShortArray>(2)
        var totalShorts = 0
        var dropped = 0
        var failure: Exception? = null
        try {
            while (true) {
                // 丢弃同步头之前的垃圾字节
                while (pending.size >= 2 &&
                    !(pending.at(0) == 0xFF && (pending.at(1) and 0xE0) == 0xE0)
                ) {
                    pending.skip(1)
                    dropped++
                }
                if (pending.size < 4) break

                val info = parseFrameInfo()
                if (info == null) {
                    pending.skip(1)
                    dropped++
                    continue
                }
                if (pending.size < info.frameSize) break // 等下一个输入块把整帧补齐

                ensureChain(info.sampleRate, info.channels)

                // 只把这一帧投给 JLayer：读帧结束后输入队列为空，
                // 它就不会在缺数据时补零，字节也就不会错位
                val frame = ByteArray(info.frameSize)
                pending.copyTo(frame, 0, info.frameSize)
                pending.skip(info.frameSize)
                feed.push(frame, 0, frame.size)

                val bs = bitstream ?: break
                val decoder = jlDecoder ?: break
                val sample = sampleBuffer ?: break
                val header = bs.readFrame() ?: continue
                // decodeFrame 内部也会 clear_buffer，这里显式清一遍更直观
                sample.clear_buffer()
                decoder.decodeFrame(header, bs)
                bs.closeFrame()

                val length = sample.bufferLength
                if (length > 0) {
                    // 慢速自动增益：广播音频原电平常常只有 -10dBFS，比网页播放器小很多，
                    // 按每帧 RMS 平滑地抬到目标响度，并做削顶保护
                    var sumSq = 0.0
                    for (i in 0 until length) {
                        val v = sample.buffer[i].toInt()
                        sumSq += v.toDouble() * v
                    }
                    val rms = Math.sqrt(sumSq / length)
                    val target = if (rms > 1.0) (TARGET_RMS / rms).toFloat() else 1.0f
                    gain = gain * (1.0f - GAIN_RATE) + target.coerceIn(1.0f, MAX_GAIN) * GAIN_RATE

                    val pcm = ShortArray(length)
                    for (i in 0 until length) {
                        val v = sample.buffer[i].toInt() * gain
                        pcm[i] = when {
                            v > 32767f -> Short.MAX_VALUE
                            v < -32768f -> Short.MIN_VALUE
                            else -> v.toInt().toShort()
                        }
                    }
                    pcmFrames.add(pcm)
                    totalShorts += length
                    Mp2DebugDump.write(frame, pcm, length)
                }
            }
        } catch (e: Exception) {
            // 坏帧跳过即可，不要中断整个播放
            failure = e
        }

        if (failure != null && failedFrames < MAX_LOGGED_FAILURES) {
            Log.w(TAG, "MP2 丢帧 #$failedFrames", failure)
            failedFrames++
        }
        if (BuildConfig.DEBUG && dropped > 0) Log.d(TAG, "丢弃 $dropped 字节")
        if (totalShorts <= 0) {
            skip(outputBuffer, inputBuffer.timeUs)
            return null
        }

        val target = outputBuffer.init(inputBuffer.timeUs, totalShorts * 2)
        val shorts = target.asShortBuffer()
        var offset = 0
        for (pcm in pcmFrames) {
            shorts.position(offset)
            shorts.put(pcm, 0, pcm.size)
            offset += pcm.size
        }
        target.position(0)
        target.limit(totalShorts * 2)
        return null
    }

    /**
     * 采样率/声道变化（换频道、甚至同频道中途切换）时必须整条链重建：
     * 旧的合成滤波器是按旧参数初始化的，继续用会变声甚至越界。
     */
    private fun ensureChain(rate: Int, channels: Int) {
        if (bitstream != null && rate == chainRate && channels == chainChannels) return
        chainRate = rate
        chainChannels = channels
        feed.clear()

        val sample = SampleBuffer(rate, channels)
        val decoder = Decoder()
        decoder.setOutputBuffer(sample)
        val bs = Bitstream(QueueInputStream(feed))

        sampleBuffer = sample
        jlDecoder = decoder
        bitstream = bs
        outputSampleRate = rate
        outputChannels = channels

        if (BuildConfig.DEBUG && (rate != loggedRate || channels != loggedChannels)) {
            Log.d(TAG, "音频输出 ${rate}Hz ${channels}ch")
            loggedRate = rate
            loggedChannels = channels
        }
    }

    /** 解析队首的 MPEG 音频帧头；返回整帧字节数（含 4 字节头） */
    private fun parseFrameInfo(): FrameInfo? {
        if (pending.at(0) != 0xFF || (pending.at(1) and 0xE0) != 0xE0) return null
        val versionBits = (pending.at(1) and 0x18) shr 3
        val layerBits = (pending.at(1) and 0x06) shr 1
        if (versionBits == 1 || layerBits == 0) return null

        val bitrateIndex = (pending.at(2) and 0xF0) shr 4
        val sampleRateIndex = (pending.at(2) and 0x0C) shr 2
        val padding = (pending.at(2) and 0x02) shr 1
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null

        val mpeg1 = versionBits == 3
        // 层号：位值 3=Layer I、2=Layer II、1=Layer III，故 layer = 4 - layerBits
        val layer = 4 - layerBits
        val bitrates = if (mpeg1) BITRATES_MPEG1[layer - 1] else BITRATES_MPEG2[layer - 1]
        val bitrate = bitrates[bitrateIndex]
        if (bitrate <= 0) return null

        val sampleRate = when (versionBits) {
            3 -> SAMPLE_RATES_MPEG1[sampleRateIndex]
            2 -> SAMPLE_RATES_MPEG2[sampleRateIndex]
            else -> SAMPLE_RATES_MPEG25[sampleRateIndex]
        }

        // JLayer 的 Header.framesize 是“不含 4 字节头”的帧长（它内部会 -4），
        // 这里算的是整帧长度，两者相差正好 4 字节
        val frameSize = if (layer == 1) {
            (12 * bitrate / sampleRate + padding) * 4
        } else {
            var size = 144 * bitrate / sampleRate
            if (!mpeg1) size /= 2
            size + padding
        }
        if (frameSize < 8 || frameSize > MAX_FRAME_SIZE) return null

        val channels = if (((pending.at(3) and 0xC0) shr 6) == 3) 1 else 2
        return FrameInfo(frameSize, sampleRate, channels)
    }

    private fun skip(outputBuffer: SimpleDecoderOutputBuffer, timeUs: Long) {
        outputBuffer.init(timeUs, 0)
        outputBuffer.shouldBeSkipped = true
    }

    private class FrameInfo(val frameSize: Int, val sampleRate: Int, val channels: Int)

    companion object {
        private const val TAG = "Mp2Decoder"
        private const val NUM_BUFFERS = 16
        private const val MAX_LOGGED_FAILURES = 3
        private const val MAX_FRAME_SIZE = 2048

        /** 自动增益的目标 RMS（约 -21dBFS），再高会让语音峰值削顶 */
        private const val TARGET_RMS = 2800.0
        private const val MAX_GAIN = 4.0f
        /** 每帧（约 24ms）调整的比例，越大越灵敏、越容易听到音量跳动 */
        private const val GAIN_RATE = 0.12f

        /** 码率表，单位 bit/s；下标 0 = free format（不支持），15 = 非法 */
        private val BITRATES_MPEG1 = arrayOf(
            intArrayOf(0, 32000, 64000, 96000, 128000, 160000, 192000, 224000, 256000, 288000, 320000, 352000, 384000, 416000, 448000, 0),
            intArrayOf(0, 32000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 160000, 192000, 224000, 256000, 320000, 384000, 0),
            intArrayOf(0, 32000, 40000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 160000, 192000, 224000, 256000, 320000, 0)
        )

        /** MPEG-2 / 2.5（LSF）码率表 */
        private val BITRATES_MPEG2 = arrayOf(
            intArrayOf(0, 32000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 176000, 192000, 224000, 256000, 0),
            intArrayOf(0, 8000, 16000, 24000, 32000, 40000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 0),
            intArrayOf(0, 8000, 16000, 24000, 32000, 40000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 0)
        )

        private val SAMPLE_RATES_MPEG1 = intArrayOf(44100, 48000, 32000)
        private val SAMPLE_RATES_MPEG2 = intArrayOf(22050, 24000, 16000)
        private val SAMPLE_RATES_MPEG25 = intArrayOf(11025, 12000, 8000)

        /** SimpleDecoder 只把数组当容量占位，元素随后会被 createInputBuffer/createOutputBuffer 覆盖 */
        private fun placeholderInputBuffers(size: Int): Array<DecoderInputBuffer> =
            Array(size) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL) }

        private fun placeholderOutputBuffers(size: Int): Array<SimpleDecoderOutputBuffer> =
            Array(size) { SimpleDecoderOutputBuffer { _ -> } }
    }
}

/** 可反复读写的小型字节队列，避免每帧新建大数组 */
private class ByteQueue {
    private var buf = ByteArray(1024)
    private var head = 0
    private var tail = 0

    val size: Int get() = tail - head

    fun push(bytes: ByteArray, from: Int, count: Int) {
        ensureRoom(count)
        System.arraycopy(bytes, from, buf, tail, count)
        tail += count
    }

    fun at(index: Int): Int = buf[head + index].toInt() and 0xFF

    fun skip(count: Int) {
        head += count
        if (head >= tail) {
            head = 0
            tail = 0
        }
    }

    fun copyTo(out: ByteArray, outOffset: Int, count: Int) {
        System.arraycopy(buf, head, out, outOffset, count)
    }

    fun clear() {
        head = 0
        tail = 0
    }

    private fun ensureRoom(count: Int) {
        if (tail + count <= buf.size) return
        val used = size
        if (used + count <= buf.size) {
            System.arraycopy(buf, head, buf, 0, used)
        } else {
            var capacity = buf.size
            while (capacity < used + count) capacity *= 2
            val grown = ByteArray(capacity)
            System.arraycopy(buf, head, grown, 0, used)
            buf = grown
        }
        head = 0
        tail = used
    }
}

/**
 * 队列空了就返回 -1（表示“暂时没数据”，而不是永久 EOF）。
 * JLayer 只在队列里有一整帧时才会来读，所以不会触发它补零的逻辑。
 */
private class QueueInputStream(private val queue: ByteQueue) : InputStream() {

    override fun read(): Int {
        if (queue.size == 0) return -1
        val value = queue.at(0)
        queue.skip(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (queue.size == 0) return -1
        val count = if (len < queue.size) len else queue.size
        queue.copyTo(b, off, count)
        queue.skip(count)
        return count
    }

    override fun available(): Int = queue.size
}

/** 仅调试用：把投喂给 JLayer 的压缩帧和解出的 PCM 落盘，便于离线逐采样比对 */
internal object Mp2DebugDump {
    private var esOut: java.io.FileOutputStream? = null
    private var pcmOut: java.io.FileOutputStream? = null
    private var inOut: java.io.FileOutputStream? = null
    private var frames = 0
    private var inputs = 0

    fun open(dir: String) {
        if (!BuildConfig.DEBUG || esOut != null) return
        try {
            val folder = java.io.File(dir)
            folder.mkdirs()
            esOut = java.io.FileOutputStream(java.io.File(folder, "mp2_es.bin"), false)
            pcmOut = java.io.FileOutputStream(java.io.File(folder, "mp2_pcm.raw"), false)
            inOut = java.io.FileOutputStream(java.io.File(folder, "mp2_in.bin"), false)
            Log.d("Mp2Dump", "dump 目录 $dir")
        } catch (e: Exception) {
            Log.w("Mp2Dump", "创建 dump 文件失败", e)
        }
    }

    /** 记录 ExoPlayer 交给音频渲染器的原始样本字节 */
    fun writeInput(chunk: ByteArray) {
        if (!BuildConfig.DEBUG || inputs >= MAX_INPUTS) return
        val out = inOut ?: return
        try {
            out.write(chunk)
            out.flush()
            inputs++
        } catch (e: Exception) {
            Log.w("Mp2Dump", "写输入 dump 失败", e)
        }
    }

    fun write(frame: ByteArray, pcm: ShortArray, length: Int) {
        if (!BuildConfig.DEBUG) return
        val es = esOut ?: return
        val out = pcmOut ?: return
        if (frames >= MAX_FRAMES) return
        try {
            es.write(frame)
            val bytes = ByteArray(length * 2)
            for (i in 0 until length) {
                val v = pcm[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
            es.flush()
            out.flush()
            frames++
            if (frames <= 3) Log.d("Mp2Dump", "写入 frame=$frames es=${frame.size} pcm=$length")
            if (frames == MAX_FRAMES) Log.d("Mp2Dump", "dump 完成 frames=$frames")
        } catch (e: Exception) {
            Log.w("Mp2Dump", "写 dump 失败", e)
        }
    }

    private const val MAX_FRAMES = 2000
    private const val MAX_INPUTS = 200
}

/** 在默认渲染器列表最前面插入自带的 MP2 软解渲染器，其余保持不变 */
@UnstableApi
class FreeTVRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        if (BuildConfig.DEBUG) Mp2DebugDump.open(context.filesDir.absolutePath)
        out.add(Mp2AudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }
}
