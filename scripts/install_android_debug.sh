#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf '错误：%s\n' "$1" >&2
  exit 1
}

info() {
  printf '==> %s\n' "$1"
}

usage() {
  cat <<'EOF'
用法：bash scripts/install_android_debug.sh [--serial 设备序列号 | --connect HOST:PORT]

默认要求恰好连接一台已授权的 Android 设备。多台设备时使用 --serial 或 --connect 指定目标。
EOF
}

requested_serial=""
connect_address=""
while (($#)); do
  case "$1" in
    --serial)
      (($# >= 2)) || fail "--serial 后需要填写 adb 设备序列号。"
      [[ -n "$2" ]] || fail "--serial 的设备序列号不能为空。"
      requested_serial="$2"
      shift 2
      ;;
    --connect)
      (($# >= 2)) || fail "--connect 后需要填写 Wi-Fi ADB 的 HOST:PORT。"
      [[ -n "$2" ]] || fail "--connect 的 HOST:PORT 不能为空。"
      connect_address="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "无法识别参数：$1"
      ;;
  esac
done

[[ -z "$requested_serial" || -z "$connect_address" ]] || fail "--serial 和 --connect 只能选择一个。"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
android_dir="$repo_root/android"
profile_file="$repo_root/profiles/hok_minimap_hd_bootstrap.android.json"
model_param="$android_dir/app/src/main/assets/minimap-yolox-nano-320.param"
model_bin="$android_dir/app/src/main/assets/minimap-yolox-nano-320.bin"

[[ -x "$android_dir/gradlew" ]] || fail "找不到可执行的 android/gradlew；请确认仓库文件完整。"
[[ -f "$profile_file" ]] || fail "找不到开发 profile：$profile_file"
[[ -f "$model_param" && -f "$model_bin" ]] || fail \
  "缺少本机开发模型文件。请先把匹配当前 metadata 的 minimap-yolox-nano-320.param 和 .bin 放到 android/app/src/main/assets/；模型文件不应提交到 Git。"

studio_jbr=""
jbr_candidates=()
if [[ -n "${ANDROID_STUDIO_JBR:-}" ]]; then
  jbr_candidates+=("$ANDROID_STUDIO_JBR")
fi
if [[ -n "${ANDROID_STUDIO:-}" ]]; then
  jbr_candidates+=("$ANDROID_STUDIO/jbr" "$ANDROID_STUDIO/Contents/jbr/Contents/Home")
fi
jbr_candidates+=(
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  "$HOME/Library/Application Support/Google/AndroidStudio/jbr"
  "$HOME/.local/share/Google/AndroidStudio/jbr"
  "/opt/android-studio/jbr"
  "/usr/local/android-studio/jbr"
)
if [[ -n "${JAVA_HOME:-}" ]]; then
  jbr_candidates+=("$JAVA_HOME")
fi
for candidate in "${jbr_candidates[@]}"; do
  if [[ -x "$candidate/bin/java" ]]; then
    studio_jbr="$candidate"
    break
  fi
done
[[ -n "$studio_jbr" ]] || fail \
  "找不到 Android Studio JBR。请安装 Android Studio，或设置 ANDROID_STUDIO_JBR 指向其 JBR；也可设置 JAVA_HOME 指向兼容的 JDK 17。"

sdk_candidates=()
if [[ -n "${ANDROID_HOME:-}" ]]; then
  sdk_candidates+=("$ANDROID_HOME")
fi
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  sdk_candidates+=("$ANDROID_SDK_ROOT")
fi
if [[ -f "$android_dir/local.properties" ]]; then
  local_sdk="$(sed -n 's/^sdk\.dir=//p' "$android_dir/local.properties" | tail -n 1 | sed 's/\\:/\:/g; s/\\ / /g')"
  if [[ -n "$local_sdk" ]]; then
    sdk_candidates+=("$local_sdk")
  fi
fi
if [[ -n "${HOME:-}" ]]; then
  sdk_candidates+=("$HOME/Library/Android/sdk" "$HOME/Android/Sdk")
fi

sdk_root=""
adb_bin=""
for candidate in "${sdk_candidates[@]}"; do
  if [[ -x "$candidate/platform-tools/adb" ]]; then
    sdk_root="$candidate"
    adb_bin="$candidate/platform-tools/adb"
    break
  fi
done
if [[ -z "$adb_bin" ]] && command -v adb >/dev/null 2>&1; then
  adb_bin="$(command -v adb)"
  adb_parent="$(cd "$(dirname "$adb_bin")/.." && pwd)"
  if [[ -d "$adb_parent/platform-tools" ]]; then
    sdk_root="$adb_parent"
  fi
fi
[[ -n "$adb_bin" && -x "$adb_bin" ]] || fail \
  "找不到 Android SDK/adb。请安装 SDK Platform-Tools，或设置 ANDROID_HOME/ANDROID_SDK_ROOT 指向 Android SDK。"
[[ -n "$sdk_root" ]] || fail \
  "已找到 adb，但无法定位其所属 Android SDK。请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT。"

export JAVA_HOME="$studio_jbr"
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
export PATH="$studio_jbr/bin:$sdk_root/platform-tools:$PATH"

if [[ -n "$connect_address" ]]; then
  info "连接 Wi-Fi ADB 设备：$connect_address"
  connect_output="$("$adb_bin" connect "$connect_address" 2>&1)" || fail \
    "adb connect 失败：${connect_output}。请确认手机与电脑网络可达，且使用的是无线调试主页面显示的连接端口。"
  case "$connect_output" in
    *"connected to"*|*"already connected to"*) ;;
    *) fail "adb connect 未建立连接：${connect_output}。Android 11+ 请先完成无线调试配对，再使用主页面连接端口。" ;;
  esac
  requested_serial="$connect_address"
fi

device_output="$("$adb_bin" devices -l 2>&1)" || fail "运行 adb devices 失败：$device_output"
authorized_serials=()
device_states=()
while read -r serial state rest; do
  [[ -z "${serial:-}" || "$serial" == "List" ]] && continue
  if [[ "${state:-}" == "device" ]]; then
    authorized_serials+=("$serial")
  else
    device_states+=("$serial (${state:-未知状态})")
  fi
done <<< "$device_output"

if ((${#authorized_serials[@]} == 0)); then
  if ((${#device_states[@]} > 0)); then
    fail "没有已授权设备。当前设备状态：${device_states[*]}。请解锁手机、开启 USB 调试，并在手机上确认此电脑的调试授权。"
  fi
  fail "没有检测到 Android 设备。请连接手机并开启 USB 调试，再运行 adb devices -l 检查。"
fi

if [[ -n "$requested_serial" ]]; then
  selected_device=""
  for serial in "${authorized_serials[@]}"; do
    if [[ "$serial" == "$requested_serial" ]]; then
      selected_device="$serial"
      break
    fi
  done
  [[ -n "$selected_device" ]] || fail \
    "指定设备 $requested_serial 不在已授权设备列表中。请检查序列号和设备授权状态。"
else
  if ((${#authorized_serials[@]} != 1)); then
    fail "检测到 ${#authorized_serials[@]} 台已授权设备：${authorized_serials[*]}。请使用 --serial 或 --connect 指定本次安装目标。"
  fi
  selected_device="${authorized_serials[0]}"
fi

api_level="$("$adb_bin" -s "$selected_device" shell getprop ro.build.version.sdk 2>&1 | tr -d '\r')"
[[ "$api_level" =~ ^[0-9]+$ ]] || fail \
  "无法读取设备 $selected_device 的 Android API 等级；adb 返回：${api_level:-空值}"
((api_level >= 29)) || fail "设备 Android API 为 ${api_level}；本应用要求 Android 10 / API 29 或更高版本。"

abi_list="$("$adb_bin" -s "$selected_device" shell getprop ro.product.cpu.abilist 2>&1 | tr -d '\r')"
if [[ -z "$abi_list" ]]; then
  abi_list="$("$adb_bin" -s "$selected_device" shell getprop ro.product.cpu.abi 2>&1 | tr -d '\r')"
fi
case ",$abi_list," in
  *,arm64-v8a,*) ;;
  *) fail "设备不支持 arm64-v8a（设备 ABI：${abi_list:-未知}）；当前 APK 只构建 arm64-v8a。" ;;
esac

info "目标设备：${selected_device}（Android API ${api_level}，ABI ${abi_list}）"
info "使用 Android Studio JBR：$studio_jbr"
info "使用 Android SDK：$sdk_root"
info "构建单元测试和 Debug APK。"
if ! (cd "$android_dir" && ./gradlew :app:testDebugUnitTest :app:assembleDebug); then
  fail "Gradle 构建失败。请检查上方错误；YOLOX 开发试装需要匹配 metadata 的本机 param/bin 文件。"
fi

apk_file="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk_file" ]] || fail "构建命令结束，但没有生成 APK：$apk_file"

info "安装 Debug APK。"
# Push installation avoids a MIUI/HyperOS restriction that can reject a
# streamed install for a newly introduced application id.
"$adb_bin" -s "$selected_device" install --no-streaming -r "$apk_file" || fail \
  "APK 安装失败。请检查手机是否解锁、USB 调试授权是否有效，以及设备存储空间。"

remote_profile="/sdcard/Download/hok_minimap_hd_bootstrap.android.json"
info "复制开发 profile 到手机 Download 目录。"
"$adb_bin" -s "$selected_device" push "$profile_file" "$remote_profile" || fail \
  "复制开发 profile 失败；请确认设备已解锁且 Download 目录可写。"

info "启动听野。"
"$adb_bin" -s "$selected_device" shell am start -n com.openkhub.sensefield/.GameSelectionActivity || fail \
  "应用已安装，但启动失败。请在手机上检查应用安装状态。"

cat <<EOF

完成：Debug APK 已安装，开发 profile 已复制到 Download，应用已启动。
下一步选择“王者荣耀”，进入“配置与调参”，导入 Download 内的
hok_minimap_hd_bootstrap.android.json，再手动开启实验识别器。
本脚本不会更改应用开关，也不会发起 MediaProjection 授权。
EOF
