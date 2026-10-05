#!/usr/bin/env bash
# 回归门禁：用同一份 android.graphics stub 编译仓库现有 JVM 单测（Match3BoardTest / Match3AutoDetectTest），
# 保证改了 Match3Sampler / Match3Coach 之后既有断言一条不掉。测试源文件同样做 sha256 核对，防止测到旧副本。
set -euo pipefail
cd "$(dirname "$0")"

REPO=../../..
MAIN=$REPO/android/app/src/main/java/com/openkhub/sensefield
TEST=$REPO/android/app/src/test/java/com/openkhub/sensefield
J=/c/Java/jdk-17.0.20+8/bin
[ -x "$J/javac.exe" ] || J="$JAVA_HOME/bin"

M2=~/.gradle/caches/modules-2/files-2.1
JUNIT=$(find $M2/junit/junit/4.13.2 -name 'junit-4.13.2.jar' | head -1)
HAMCREST=$(find $M2/org.hamcrest -name 'hamcrest-core-1.3.jar' | head -1)
[ -n "$JUNIT" ] && [ -n "$HAMCREST" ] || { echo "缺 junit/hamcrest jar"; exit 1; }
CP_WIN="$(cygpath -w "$JUNIT");$(cygpath -w "$HAMCREST")"

TESTS="Match3BoardTest.java Match3AutoDetectTest.java Match3PieceNameTest.java"
mkdir -p test/com/openkhub/sensefield out-tests
for f in $TESTS; do
  cp "$TEST/$f" "test/com/openkhub/sensefield/$f"
done
# 被测主源文件也一律重新同步：并行会话会往同一分支继续 commit，用旧副本测＝假绿
for f in Match3Sampler.java Match3Board.java Match3Coach.java; do
  cp "$MAIN/$f" "src/com/openkhub/sensefield/$f"
done
echo "--- 源文件同步核对（两列哈希必须一致）---"
for f in $TESTS; do
  sha256sum "$TEST/$f" "test/com/openkhub/sensefield/$f"
done
for f in Match3Sampler.java Match3Board.java Match3Coach.java; do
  sha256sum "$MAIN/$f" "src/com/openkhub/sensefield/$f"
done

"$J/javac" -encoding UTF-8 -nowarn -cp "$CP_WIN" -d out-tests \
  src/android/graphics/*.java src/android/content/*.java \
  src/com/openkhub/sensefield/Match3Sampler.java \
  src/com/openkhub/sensefield/Match3Board.java \
  src/com/openkhub/sensefield/Match3Coach.java \
  test/com/openkhub/sensefield/*.java

echo "--- 运行既有单测 ---"
"$J/java" -cp "out-tests;$CP_WIN" org.junit.runner.JUnitCore \
  com.openkhub.sensefield.Match3BoardTest \
  com.openkhub.sensefield.Match3AutoDetectTest \
  com.openkhub.sensefield.Match3PieceNameTest \
  com.openkhub.sensefield.Match3GuardTest
