#!/usr/bin/env bash
# Bolt IPTV 发版脚本
#
# 用法：
#   GH_TOKEN=ghp_xxx ./scripts/release.sh 1.0.1
#   ./scripts/release.sh 1.0.1 "修复 XXX"      # 第二个参数作为版本说明
#
# 做的事：
#   1. 校验 token / 签名配置 / 工作区干净
#   2. 自动递增 versionCode，更新 versionName
#   3. 编译已签名 Release APK 并校验签名
#   4. 提交版本号、打 tag、推送 main 与 tag
#   5. 创建 GitHub Release 并上传 APK（含失败重试）
set -euo pipefail

REPO="AaronHvang/Bolt-IPTV"
APP_NAME="Bolt-IPTV"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
EXTRA_NOTE="${2:-}"

if [ -z "$VERSION" ]; then
  echo "用法: $0 <版本号，如 1.0.1> [版本说明]" >&2
  exit 1
fi
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "版本号格式错误，应为 x.y.z（当前: ${VERSION}）" >&2
  exit 1
fi

TAG="v${VERSION}"

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m错误: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 1. 环境校验 ----------
say "检查环境"

TOKEN="${GH_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$HOME/.gh_token" ]; then
  TOKEN="$(tr -d '\n\r ' < "$HOME/.gh_token")"
fi
[ -n "$TOKEN" ] || die "未找到 token：请设置 GH_TOKEN 或写入 ~/.gh_token"

LOGIN="$(curl -sS -H "Authorization: token $TOKEN" https://api.github.com/user \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("login",""))' 2>/dev/null || true)"
[ -n "$LOGIN" ] || die "token 无效或网络不可用"

[ -f keystore.properties ] || die "缺少 keystore.properties，无法签名 Release 包"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  die "本地已存在 tag $TAG"
fi
[ -z "$(git status --porcelain)" ] || die "工作区不干净，请先提交或清理改动"

if curl -sf -o /dev/null -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/$REPO/releases/tags/$TAG"; then
  die "GitHub 上已存在 Release $TAG"
fi

echo "账号: $LOGIN  仓库: $REPO  目标版本: $VERSION"

# ---------- 2. 版本号 ----------
say "更新版本号"

GRADLE="app/build.gradle.kts"
CUR_CODE="$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE" | head -1 | grep -oE '[0-9]+')"
CUR_NAME="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
[ -n "$CUR_CODE" ] || die "无法从 $GRADLE 读取 versionCode"
NEW_CODE=$((CUR_CODE + 1))

if [ "$(uname)" = "Darwin" ]; then
  sed -i '' -E "s/versionCode[[:space:]]*=[[:space:]]*$CUR_CODE/versionCode = $NEW_CODE/" "$GRADLE"
  sed -i '' -E "s/versionName[[:space:]]*=[[:space:]]*\"$CUR_NAME\"/versionName = \"$VERSION\"/" "$GRADLE"
else
  sed -i -E "s/versionCode[[:space:]]*=[[:space:]]*$CUR_CODE/versionCode = $NEW_CODE/" "$GRADLE"
  sed -i -E "s/versionName[[:space:]]*=[[:space:]]*\"$CUR_NAME\"/versionName = \"$VERSION\"/" "$GRADLE"
fi

echo "versionCode: $CUR_CODE -> $NEW_CODE"
echo "versionName: $CUR_NAME -> $VERSION"

# ---------- 3. 编译 ----------
say "编译 Release APK"
./gradlew :app:assembleRelease --console=plain

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "未找到构建产物 $APK"

APKSIGNER="$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1 || true)"
[ -n "$APKSIGNER" ] || APKSIGNER="$(command -v apksigner || true)"
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$APK" >/dev/null 2>&1 || die "APK 签名校验失败"
  echo "签名校验通过"
fi

STAGE="/tmp/${APP_NAME}-${VERSION}.apk"
cp "$APK" "$STAGE"
echo "产物: $STAGE ($(du -h "$STAGE" | cut -f1))"

# ---------- 4. 提交与推送 ----------
say "提交版本号并打 tag"
git add "$GRADLE"
# 未配置全局 git 身份时用脚本内置身份，避免 commit 失败
GIT_ID=()
if [ -z "$(git config user.email || true)" ]; then
  GIT_ID=(-c user.name="Aaron Huang" -c user.email="aaron@users.noreply.github.com")
fi
git "${GIT_ID[@]}" commit -q -m "chore: release $TAG"
git tag -a "$TAG" -m "$APP_NAME $VERSION"
# 推送走 token，避免本机没有凭据助手时卡在密码输入
PUSH_URL="https://x-access-token:${TOKEN}@github.com/${REPO}.git"
git push "$PUSH_URL" main
git push "$PUSH_URL" "$TAG"
echo "已推送 main 与 $TAG"

# ---------- 5. 创建 Release ----------
say "创建 GitHub Release"

PREV_TAG="$(git describe --tags --abbrev=0 "$TAG^" 2>/dev/null || true)"
if [ -n "$PREV_TAG" ]; then
  CHANGES="$(git log --no-merges --pretty=format:'- %s' "$PREV_TAG..$TAG")"
else
  CHANGES="$(git log --no-merges --pretty=format:'- %s' -20 "$TAG")"
fi
if [ -n "$EXTRA_NOTE" ]; then
  CHANGES="### 更新说明
$EXTRA_NOTE

### 提交记录
$CHANGES"
fi

BODY="### 安装
\`\`\`bash
adb connect <电视IP>:5555
adb install -r ${APP_NAME}-${VERSION}.apk
\`\`\`

### 说明
- 支持 Android 6.0 (API 23) 及以上，横屏电视优化
- 本项目不内置任何频道源，请自行准备合法授权的播放列表
- 采用 GPL-3.0 + 商业双许可，详见 README
${CHANGES:+}

${CHANGES}"

python3 - "$TAG" "$VERSION" "$BODY" > /tmp/bolt_release.json <<'PY'
import json, sys
tag, version, body = sys.argv[1], sys.argv[2], sys.argv[3]
json.dump({
    "tag_name": tag,
    "name": f"{tag.lstrip('v')}",
    "body": body,
    "draft": False,
    "prerelease": False,
}, sys.stdout, ensure_ascii=False)
PY

REL_ID="$(curl -sS -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  --data-binary @/tmp/bolt_release.json \
  "https://api.github.com/repos/$REPO/releases" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("id") or d.get("message",""))')"

printf '%s' "$REL_ID" | grep -Eq '^[0-9]+$' || die "创建 Release 失败: $REL_ID"
echo "Release id: $REL_ID"

# ---------- 6. 上传 APK ----------
say "上传 APK"

UPLOAD_URL="https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=${APP_NAME}-${VERSION}.apk"

wait_uploaded() {
  local i
  for i in $(seq 1 60); do
    local state
    state="$(curl -sS -H "Authorization: token $TOKEN" \
      "https://api.github.com/repos/$REPO/releases/$REL_ID/assets" \
      | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["state"] if d else "none")')"
    [ "$state" = "uploaded" ] && return 0
    sleep 5
  done
  return 1
}

drop_assets() {
  local ids
  ids="$(curl -sS -H "Authorization: token $TOKEN" \
    "https://api.github.com/repos/$REPO/releases/$REL_ID/assets" \
    | python3 -c 'import sys,json;print(" ".join(str(a["id"]) for a in json.load(sys.stdin)))')"
  for id in $ids; do
    curl -sS -o /dev/null -X DELETE -H "Authorization: token $TOKEN" \
      "https://api.github.com/repos/$REPO/releases/assets/$id" || true
  done
}

for attempt in 1 2 3; do
  echo "第 $attempt 次上传..."
  curl -sS --http1.1 --max-time 1800 -o /tmp/bolt_asset.json \
    -X POST -H "Authorization: token $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$STAGE" "$UPLOAD_URL" || echo "上传连接中断，检查服务端状态"

  if wait_uploaded; then
    echo "上传完成"
    break
  fi

  echo "上传未完成，清理后重试"
  drop_assets
  if [ "$attempt" = "3" ]; then
    die "APK 上传失败，请到网页手动拖拽 $STAGE"
  fi
done

# ---------- 7. 完成 ----------
say "发版完成"
URL="https://github.com/$REPO/releases/tag/$TAG"
DL="https://github.com/$REPO/releases/latest/download/${APP_NAME}-${VERSION}.apk"
echo "Release: $URL"
echo "下载:    $DL"
curl -sS -o /dev/null -w "下载直链校验: HTTP %{http_code}, %{size_download} bytes\n" -L "$DL"
rm -f /tmp/bolt_release.json /tmp/bolt_asset.json
