#!/usr/bin/env bash
set -euo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ "$(id -u)" == 0 ]] || { printf '%s\n' '请在自己的生产服务器用 sudo 运行本脚本。' >&2; exit 1; }
command -v python3 >/dev/null
command -v openssl >/dev/null
docker compose version >/dev/null
umask 077
mkdir -p "$deploy_dir/runtime/tls"
if [[ ! -f "$deploy_dir/runtime/gateway.json" ]]; then
    python3 "$deploy_dir/configure.py" --output "$deploy_dir/runtime/gateway.json" \
        --codes-output "$deploy_dir/runtime/体验连接码.txt"
fi
if [[ -f "$deploy_dir/runtime/tls/backend.crt" && ! -f "$deploy_dir/runtime/tls/backend.key" ]] \
        || [[ ! -f "$deploy_dir/runtime/tls/backend.crt" && -f "$deploy_dir/runtime/tls/backend.key" ]]; then
    printf '%s\n' '后端证书或私钥缺失，请恢复完整文件后再运行。' >&2
    exit 1
fi
if [[ ! -f "$deploy_dir/runtime/tls/backend.crt" ]]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj '/CN=localhost' \
        -addext 'subjectAltName=DNS:localhost,DNS:gateway,IP:127.0.0.1' \
        -keyout "$deploy_dir/runtime/tls/backend.key" -out "$deploy_dir/runtime/tls/backend.crt" >/dev/null 2>&1
fi
chown 10001:10001 "$deploy_dir/runtime/gateway.json" "$deploy_dir/runtime/tls" \
    "$deploy_dir/runtime/tls/backend.key"
chmod 0700 "$deploy_dir/runtime"
chmod 0500 "$deploy_dir/runtime/tls"
chmod 0400 "$deploy_dir/runtime/gateway.json" "$deploy_dir/runtime/tls/backend.key"
chmod 0444 "$deploy_dir/runtime/tls/backend.crt"
printf '%s\n' '配置和后端 TLS 已准备好；尚未启动服务、修改反代、配置 DNS 或签发公网证书。'
