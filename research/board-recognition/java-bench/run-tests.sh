#!/usr/bin/env bash
# Synthetic bitmap guards only; compile repository sources directly to avoid stale copies.
set -euo pipefail
bench_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$bench_dir/../../.." && pwd)"
main_dir="$repo_dir/android/app/src/main/java/com/openkhub/sensefield"
test_dir="$repo_dir/android/app/src/test/java/com/openkhub/sensefield"
java_bin="${JAVA_HOME:?Set JAVA_HOME to your JDK}/bin"
cache_dir="$HOME/.gradle/caches/modules-2/files-2.1"
junit_jar="$(rg --files "$cache_dir/junit/junit/4.13.2" | rg '/junit-4\.13\.2\.jar$' | head -n 1)"
hamcrest_jar="$(rg --files "$cache_dir/org.hamcrest" | rg '/hamcrest-core-1\.3\.jar$' | head -n 1)"
classpath="$junit_jar:$hamcrest_jar"
classpath_separator=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) classpath_separator=";"; classpath="$(cygpath -w "$junit_jar");$(cygpath -w "$hamcrest_jar")" ;;
esac
mkdir -p "$bench_dir/out-tests"
"$java_bin/javac" -encoding UTF-8 -nowarn -cp "$classpath" -d "$bench_dir/out-tests" \
  "$bench_dir"/src/android/graphics/*.java "$bench_dir"/src/android/content/*.java \
  "$main_dir/Match3Sampler.java" "$main_dir/Match3Board.java" "$main_dir/Match3Coach.java" \
  "$test_dir/Match3BoardTest.java" "$test_dir/Match3AutoDetectTest.java" \
  "$test_dir/Match3PieceNameTest.java" "$bench_dir/test/com/openkhub/sensefield/Match3GuardTest.java"
# Template guard writes only in the ignored bench working directory.
cd "$bench_dir"
"$java_bin/java" -Djava.awt.headless=true -cp "out-tests${classpath_separator}${classpath}" org.junit.runner.JUnitCore \
  com.openkhub.sensefield.Match3BoardTest com.openkhub.sensefield.Match3AutoDetectTest \
  com.openkhub.sensefield.Match3PieceNameTest com.openkhub.sensefield.Match3GuardTest
