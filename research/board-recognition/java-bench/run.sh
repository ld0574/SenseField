#!/usr/bin/env bash
# 离线精度基准：把仓库里的真实被测源文件同步进来（带 sha256 核对）→ 编译 → 跑基准与探针。
set -euo pipefail
cd "$(dirname "$0")"

REPO=../../..
SRC=$REPO/android/app/src/main/java/com/openkhub/sensefield
J=/c/Java/jdk-17.0.20+8/bin
[ -x "$J/javac.exe" ] || J="$JAVA_HOME/bin"

FILES="Match3Sampler.java Match3Board.java Match3Coach.java"
for f in $FILES; do
  cp "$SRC/$f" "src/com/openkhub/sensefield/$f"
done
echo "--- 被测源文件同步核对（两列哈希必须一致）---"
sha256sum $SRC/Match3Sampler.java src/com/openkhub/sensefield/Match3Sampler.java
sha256sum $SRC/Match3Board.java  src/com/openkhub/sensefield/Match3Board.java
sha256sum $SRC/Match3Coach.java  src/com/openkhub/sensefield/Match3Coach.java

rm -rf out
mkdir -p out
"$J/javac.exe" -encoding UTF-8 -d out $(find src -name "*.java")

"$J/java.exe" -Dsun.stdout.encoding=UTF-8 -cp out com.openkhub.sensefield.Bench bench-files
echo
"$J/java.exe" -Dsun.stdout.encoding=UTF-8 -cp out com.openkhub.sensefield.Probe
