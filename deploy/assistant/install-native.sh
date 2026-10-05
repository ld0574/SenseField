#!/usr/bin/env bash
# Install locally on the owner's Linux server; no SSH, Docker, or proxy edits.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_dir="$(cd "$deploy_dir/../.." && pwd)"
app_dir=/opt/sensefield-assistant
config_dir=/etc/sensefield-assistant
transport=local-proxy
service_name=sensefield-assistant.service
service_user=sensefield-gateway
unit_file="/etc/systemd/system/$service_name"
python_bin="${SENSEFIELD_PYTHON:-python3}"

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
    cat <<'EOF'
用法：sudo bash deploy/assistant/install-native.sh [--app-dir 绝对路径] [--transport local-proxy|backend-tls]
默认：--app-dir /opt/sensefield-assistant --transport local-proxy
EOF
}
while (($#)); do
    case "$1" in
        --app-dir)
            (($# >= 2)) || fail '缺少 --app-dir 路径。'
            app_dir=$2
            shift 2
            ;;
        --transport)
            (($# >= 2)) || fail '缺少 --transport 值。'
            transport=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *) usage >&2; fail "未知参数：$1" ;;
    esac
done
[[ "$transport" == local-proxy || "$transport" == backend-tls ]] \
    || fail '--transport 只能是 local-proxy 或 backend-tls。'
[[ "$app_dir" == /* && "$app_dir" != / ]] || fail '--app-dir 必须是非根目录的绝对路径。'
[[ "$app_dir" =~ ^/[A-Za-z0-9._/-]+$ ]] \
    || fail '--app-dir 只支持字母、数字、点、下划线、横线和斜线。'
[[ ! "$app_dir" =~ (^|/)\.\.(/|$) ]] || fail '--app-dir 不能包含 .. 路径段。'
app_dir="${app_dir%/}"
[[ ! -L "$app_dir" ]] || fail "拒绝使用符号链接目录：$app_dir"

[[ "$(uname -s)" == Linux ]] || fail '原生部署需要 Linux + systemd。'
[[ "$(id -u)" == 0 ]] || fail '请在自己的生产服务器用 sudo 运行本脚本。'
for task_command in systemctl getent useradd install cp cmp realpath runuser "$python_bin"; do
    command -v "$task_command" >/dev/null || fail "缺少依赖：$task_command；请先按部署说明安装。"
done
if [[ "$transport" == backend-tls ]]; then
    command -v openssl >/dev/null || fail '缺少依赖：openssl；backend-tls 模式需要它创建后端证书。'
fi
[[ -d /run/systemd/system ]] || fail '当前系统未运行 systemd；容器或非 systemd 系统需单独适配。'
"$python_bin" - <<'PY'
import sys
from pathlib import Path
if not ((3, 10) <= sys.version_info[:2] < (3, 14)):
    raise SystemExit("需要 Python 3.10 至 3.13；可通过 SENSEFIELD_PYTHON 选择已安装的版本。")
try:
    import venv, ensurepip
except ImportError:
    raise SystemExit("需要所选 Python 的 venv/ensurepip；Ubuntu/Debian 请安装对应的 python3-venv。") from None
for location in (Path(sys.executable).resolve(), Path(sys.base_prefix).resolve()):
    if any(location.is_relative_to(root) for root in (Path("/home"), Path("/root"), Path("/run/user"))):
        raise SystemExit("请选择安装在 /usr 或 /opt 等服务可读取的位置的系统 Python；用户主目录下的 Python 会被服务隔离阻止。")
PY
[[ -f "$source_dir/pyproject.toml" && -d "$source_dir/python/mapassist" \
    && -f "$source_dir/scripts/assistant_gateway_container_entrypoint.py" ]] \
    || fail '部署包源码不完整。'
app_dir="$(realpath -m -- "$app_dir")"
for restricted_root in /home /root /tmp /run /var/tmp /etc; do
    [[ "$app_dir" != "$restricted_root" && "$app_dir" != "$restricted_root"/* ]] \
        || fail "--app-dir 不能位于 $restricted_root；请选择服务用户可读取的专用目录。"
done
if [[ "$app_dir" != "$source_dir" ]]; then
    [[ "$app_dir" != "$source_dir"/* && "$source_dir" != "$app_dir"/* ]] \
        || fail '安装目录不能是源码目录的父目录或子目录；请使用源码目录本身或独立目录。'
fi
systemctl is-active --quiet "$service_name" && fail '服务正在运行；请先停止服务，再重新运行安装脚本。'

# A checked-out or unpacked source tree can be adopted in place. Its generated
# targets must be absent or match the package, and an unmarked venv is kept out
# of the installer's way. This avoids treating arbitrary /data directories as
# disposable installation targets.
same_source_dir=false
[[ "$app_dir" == "$source_dir" ]] && same_source_dir=true
app_marker="$app_dir/.sensefield-native-install-v1"
config_marker="$config_dir/.sensefield-native-install-v1"
[[ ! -L "$app_marker" && ! -L "$config_marker" ]] || fail '安装标记不能是符号链接。'
if [[ -e "$app_dir" ]]; then
    [[ ! -L "$app_dir" ]] || fail "拒绝使用符号链接目录：$app_dir"
    if [[ ! -f "$app_marker" ]]; then
        [[ "$same_source_dir" == true ]] \
            || fail "目录已存在且不属于本安装器：$app_dir；请先核对已有服务。"
        [[ -f "$app_dir/pyproject.toml" && -d "$app_dir/python/mapassist" ]] \
            || fail '源码目录不完整，不能原地采用。'
        [[ ! -e "$app_dir/.venv" && ! -L "$app_dir/.venv" ]] \
            || fail "源码目录中已有未标记的 .venv：$app_dir/.venv；安装器不会覆盖它，请先备份并移到服务目录之外。"
        for target_source in \
            "$app_dir/serve.py:$source_dir/scripts/assistant_gateway_container_entrypoint.py" \
            "$app_dir/verify.py:$deploy_dir/verify.py"; do
            target="${target_source%%:*}"
            expected="${target_source#*:}"
            [[ ! -e "$target" ]] || cmp -s "$target" "$expected" \
                || fail "原地采用时发现冲突文件：$target；请先核对并自行备份。"
        done
    fi
fi
for managed_target in "$app_dir/python" "$app_dir/pyproject.toml" "$app_dir/serve.py" \
        "$app_dir/verify.py" "$app_dir/.venv"; do
    [[ ! -L "$managed_target" ]] || fail "拒绝通过符号链接写入应用目录：$managed_target"
done
for task_dir in "$config_dir"; do
    [[ ! -L "$task_dir" ]] || fail "拒绝使用符号链接目录：$task_dir"
    if [[ -e "$task_dir" && ! -f "$config_marker" ]]; then
        fail "目录已存在且不属于本安装器：$task_dir；请先核对已有服务。"
    fi
done
[[ ! -L "$config_dir/gateway.json" && ! -L "$config_dir/体验连接码.txt" ]] \
    || fail '配置或体验连接码不能是符号链接。'
if [[ "$transport" == backend-tls ]]; then
    [[ ! -L "$config_dir/tls" && ! -L "$config_dir/tls/backend.crt" \
        && ! -L "$config_dir/tls/backend.key" ]] || fail '后端 TLS 文件或目录不能是符号链接。'
    if [[ -e "$config_dir/tls/backend.crt" && ! -e "$config_dir/tls/backend.key" ]] \
            || [[ ! -e "$config_dir/tls/backend.crt" && -e "$config_dir/tls/backend.key" ]]; then
        fail '后端证书或私钥缺失，请恢复完整文件后再运行。'
    fi
fi
[[ ! -L "$unit_file" ]] || fail '已有服务单元是符号链接，请先核对。'
if [[ -e "$unit_file" && ! -f "$app_marker" ]]; then
    fail '已有同名服务不是本安装器创建的，请先核对。'
fi

# Existing credentials and player codes are immutable to this installer. Check
# only transport metadata here; never print config values or secret material.
if [[ -e "$config_dir/gateway.json" ]]; then
    [[ -f "$config_dir/gateway.json" ]] || fail '已有 gateway.json 不是普通文件；请先核对。'
    [[ -f "$config_dir/体验连接码.txt" ]] \
        || fail '配置已存在但体验连接码文件缺失；请从现有私有配置恢复原连接码，勿删除配置重新生成。'
    if ! "$python_bin" - "$config_dir/gateway.json" "$transport" <<'PY'
import json
import sys

path, transport = sys.argv[1:]
try:
    with open(path, encoding="utf-8") as source:
        config = json.load(source)
except Exception:
    raise SystemExit("现有 gateway.json 无法读取；安装器未修改它。") from None
if not isinstance(config, dict):
    raise SystemExit("现有 gateway.json 格式不正确；安装器未修改它。")
tls_keys = ("ASSISTANT_GATEWAY_TLS_CERT", "ASSISTANT_GATEWAY_TLS_KEY")
if config.get("ASSISTANT_GATEWAY_HOST") != "127.0.0.1" or config.get("ASSISTANT_GATEWAY_PORT") != "18765":
    raise SystemExit("现有配置的监听地址与原生服务不匹配；请先安全迁移配置。")
if transport == "local-proxy":
    if any(key in config for key in tls_keys) or config.get("ASSISTANT_GATEWAY_LOCAL_TLS_PROXY") != "1":
        raise SystemExit("现有配置与 --transport local-proxy 不匹配。配置和凭据已保留；请按部署说明安全迁移传输字段后重试。")
elif (config.get(tls_keys[0]) != "/etc/sensefield-assistant/tls/backend.crt"
      or config.get(tls_keys[1]) != "/etc/sensefield-assistant/tls/backend.key"
      or config.get("ASSISTANT_GATEWAY_LOCAL_TLS_PROXY") is not None):
    raise SystemExit("现有配置与 --transport backend-tls 不匹配。配置和凭据已保留；请按部署说明安全迁移传输字段后重试。")
PY
    then
        exit 1
    fi
fi

if getent passwd "$service_user" >/dev/null; then
    [[ "$(id -u "$service_user")" != 0 ]] || fail '服务用户不能是 root。'
    task_user_home="$(getent passwd "$service_user" | cut -d: -f6)"
    [[ "$task_user_home" == /nonexistent ]] || fail '同名用户已存在但用途不同，请先核对。'
    getent group "$service_user" >/dev/null || fail '服务用户缺少同名组。'
else
    nologin_bin="$(command -v nologin)" || fail '缺少 nologin。'
    useradd --system --user-group --home-dir /nonexistent --no-create-home \
        --shell "$nologin_bin" "$service_user"
fi

umask 077
if [[ "$same_source_dir" != true ]]; then
    install -d -m 0755 -o root -g root "$app_dir"
fi
install -d -m 0755 -o root -g root "$config_dir"
if [[ "$transport" == backend-tls ]]; then
    install -d -m 0755 -o root -g root "$config_dir/tls"
fi
: > "$app_marker"
: > "$config_marker"
chmod 0600 "$app_marker" "$config_marker"
if [[ "$same_source_dir" != true ]]; then
    install -m 0644 "$source_dir/pyproject.toml" "$app_dir/pyproject.toml"
    cp -R "$source_dir/python/." "$app_dir/python/"
    chown -R root:root "$app_dir/python"
    chmod -R u=rwX,go=rX "$app_dir/python"
fi
install -m 0644 "$source_dir/scripts/assistant_gateway_container_entrypoint.py" "$app_dir/serve.py"
install -m 0644 "$deploy_dir/verify.py" "$app_dir/verify.py"
if [[ ! -f "$app_dir/.venv/bin/python" ]]; then
    "$python_bin" -m venv "$app_dir/.venv"
fi
"$app_dir/.venv/bin/python" -m pip install --no-cache-dir "${app_dir}[assistant-vision-gateway]"
# Keep a source checkout's unrelated files and ownership intact. The copied
# source package and venv have already been made readable at their own paths.
chmod -R u=rwX,go=rX "$app_dir/.venv"
chmod 0644 "$app_dir/serve.py" "$app_dir/verify.py"
chmod 0600 "$app_marker"

if [[ ! -f "$config_dir/gateway.json" ]]; then
    "$python_bin" "$deploy_dir/configure.py" --runtime native --transport "$transport" \
        --output "$config_dir/gateway.json" --codes-output "$config_dir/体验连接码.txt"
fi
[[ -f "$config_dir/体验连接码.txt" ]] \
    || fail '配置已存在但体验连接码文件缺失；请从现有私有配置恢复原连接码，勿删除配置重新生成。'
if [[ "$transport" == backend-tls ]]; then
    if [[ ! -f "$config_dir/tls/backend.crt" ]]; then
        openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj '/CN=localhost' \
            -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
            -keyout "$config_dir/tls/backend.key" -out "$config_dir/tls/backend.crt" >/dev/null 2>&1
    fi
    chown "$service_user:$service_user" "$config_dir/tls/backend.key"
    chmod 0400 "$config_dir/tls/backend.key"
    chmod 0644 "$config_dir/tls/backend.crt"
fi
chown "$service_user:$service_user" "$config_dir/gateway.json"
chmod 0400 "$config_dir/gateway.json"
if [[ -f "$config_dir/体验连接码.txt" ]]; then
    chown root:root "$config_dir/体验连接码.txt"
    chmod 0600 "$config_dir/体验连接码.txt"
fi
[[ -x "$app_dir/.venv/bin/python" ]] || fail "虚拟环境解释器不存在或不可执行：$app_dir/.venv/bin/python"
[[ -r "$app_dir/verify.py" ]] || fail "服务用户验收脚本不可读：$app_dir/verify.py"
runuser -u "$service_user" -- "$app_dir/.venv/bin/python" --version >/dev/null \
    || fail '服务用户无法启动应用目录中的 Python；请检查 app-dir 和虚拟环境权限。'
runuser -u "$service_user" -- "$app_dir/.venv/bin/python" \
    "$app_dir/verify.py" --help >/dev/null \
    || fail '服务用户无法读取或启动 verify.py；请检查应用目录权限。'
if [[ "$transport" == local-proxy ]]; then
    proxy_env='Environment=ASSISTANT_GATEWAY_LOCAL_TLS_PROXY=1'
else
    proxy_env=''
fi
sed -e "s|@APP_DIR@|$app_dir|g" -e "s|@LOCAL_PROXY_ENV@|$proxy_env|g" \
    "$deploy_dir/sensefield-assistant.service" > "$unit_file"
chmod 0644 "$unit_file"
systemctl daemon-reload
printf '%s\n' "原生部署已安装（$transport），未启动服务或修改反代。下一步：" \
    "sudo systemctl enable --now $service_name" \
    "sudo systemctl status sensefield-assistant --no-pager" \
    "体验连接码保存在 $config_dir/体验连接码.txt；请逐人私下提供。"
