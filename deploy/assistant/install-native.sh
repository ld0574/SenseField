#!/usr/bin/env bash
# Install locally on the owner's Linux server; no SSH, Docker, or proxy edits.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_dir="$(cd "$deploy_dir/../.." && pwd)"
app_dir=/opt/sensefield-assistant
config_dir=/etc/sensefield-assistant
service_name=sensefield-assistant.service
service_user=sensefield-gateway
unit_file="/etc/systemd/system/$service_name"
python_bin="${SENSEFIELD_PYTHON:-python3}"

fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ $# == 0 ]] || fail '用法：sudo bash deploy/assistant/install-native.sh'
[[ "$(uname -s)" == Linux ]] || fail '原生部署需要 Linux + systemd。'
[[ "$(id -u)" == 0 ]] || fail '请在自己的生产服务器用 sudo 运行本脚本。'
for task_command in systemctl openssl getent useradd install cp "$python_bin"; do
    command -v "$task_command" >/dev/null || fail "缺少依赖：$task_command；请先按部署说明安装。"
done
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
        raise SystemExit("请选择安装在 /usr 或 /opt 的系统 Python；服务隔离不允许读取用户主目录。")
PY
[[ -f "$source_dir/pyproject.toml" && -d "$source_dir/python/mapassist" ]] \
    || fail '部署包源码不完整。'
systemctl is-active --quiet "$service_name" && fail '服务正在运行；请先停止服务，再重新运行安装脚本。'

# Refuse to overwrite unrelated installations. Markers allow a failed install
# to be resumed, preserving existing provider keys, codes, and TLS material.
for task_dir in "$app_dir" "$config_dir"; do
    [[ ! -L "$task_dir" ]] || fail "拒绝使用符号链接目录：$task_dir"
    if [[ -e "$task_dir" && ! -f "$task_dir/.sensefield-native-install-v1" ]]; then
        fail "目录已存在且不属于本安装器：$task_dir；请先核对已有服务。"
    fi
done
[[ ! -L "$unit_file" ]] || fail '已有服务单元是符号链接，请先核对。'
if [[ -e "$unit_file" && ! -f "$app_dir/.sensefield-native-install-v1" ]]; then
    fail '已有同名服务不是本安装器创建的，请先核对。'
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
install -d -m 0755 -o root -g root "$app_dir" "$app_dir/python" "$config_dir" "$config_dir/tls"
for task_dir in "$app_dir" "$config_dir"; do
    : > "$task_dir/.sensefield-native-install-v1"
    chmod 0600 "$task_dir/.sensefield-native-install-v1"
done
install -m 0644 "$source_dir/pyproject.toml" "$app_dir/pyproject.toml"
cp -R "$source_dir/python/." "$app_dir/python/"
chown -R root:root "$app_dir/python"
chmod -R u=rwX,go=rX "$app_dir/python"
install -m 0644 "$source_dir/scripts/assistant_gateway_container_entrypoint.py" "$app_dir/serve.py"
install -m 0644 "$deploy_dir/verify.py" "$app_dir/verify.py"
"$python_bin" -m venv "$app_dir/.venv"
"$app_dir/.venv/bin/python" -m pip install --no-cache-dir "${app_dir}[assistant-vision-gateway]"
# venv inherits a private umask; make application/dependency files readable to
# the non-root service user, without making any application path writable.
chmod -R u=rwX,go=rX "$app_dir"
chmod 0600 "$app_dir/.sensefield-native-install-v1"

if [[ ! -f "$config_dir/gateway.json" ]]; then
    "$python_bin" "$deploy_dir/configure.py" --runtime native \
        --output "$config_dir/gateway.json" --codes-output "$config_dir/体验连接码.txt"
fi
[[ -f "$config_dir/体验连接码.txt" ]] \
    || fail '配置已存在但体验连接码文件缺失；请从现有私有配置恢复原连接码，勿删除配置重新生成。'
if [[ -f "$config_dir/tls/backend.crt" && ! -f "$config_dir/tls/backend.key" ]] \
        || [[ ! -f "$config_dir/tls/backend.crt" && -f "$config_dir/tls/backend.key" ]]; then
    fail '后端证书或私钥缺失，请恢复完整文件后再运行。'
fi
if [[ ! -f "$config_dir/tls/backend.crt" ]]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj '/CN=localhost' \
        -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
        -keyout "$config_dir/tls/backend.key" -out "$config_dir/tls/backend.crt" >/dev/null 2>&1
fi
chown "$service_user:$service_user" "$config_dir/gateway.json" "$config_dir/tls/backend.key"
chmod 0400 "$config_dir/gateway.json" "$config_dir/tls/backend.key"
chmod 0644 "$config_dir/tls/backend.crt"
if [[ -f "$config_dir/体验连接码.txt" ]]; then
    chown root:root "$config_dir/体验连接码.txt"
    chmod 0600 "$config_dir/体验连接码.txt"
fi
install -m 0644 "$deploy_dir/sensefield-assistant.service" "$unit_file"
systemctl daemon-reload
printf '%s\n' '原生部署已安装，未启动服务或修改反代。下一步：' \
    'sudo systemctl enable --now sensefield-assistant' \
    'sudo systemctl status sensefield-assistant --no-pager' \
    '体验连接码保存在 /etc/sensefield-assistant/体验连接码.txt；请逐人私下提供。'
