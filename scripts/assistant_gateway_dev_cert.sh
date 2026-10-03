#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <dns-name-or-ip> <output-directory>" >&2
  exit 2
fi

subject="$1"
output_dir="$2"
mkdir -p "$output_dir"
chmod 700 "$output_dir"
key_path="$output_dir/private.key"
cert_path="$output_dir/fullchain.pem"

if [[ "$subject" == *:* ]]; then
  san="IP:$subject"
elif [[ "$subject" =~ ^[0-9.]+$ ]]; then
  san="IP:$subject"
else
  san="DNS:$subject"
fi

openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
  -keyout "$key_path" -out "$cert_path" \
  -subj "/CN=$subject" -addext "subjectAltName=$san"
chmod 600 "$key_path"
chmod 644 "$cert_path"
echo "Wrote a 30-day self-signed certificate to $cert_path"
echo "Install/trust this certificate on test devices before connecting over WSS."
