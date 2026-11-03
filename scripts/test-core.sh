#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p core/build/manual
find core/src/main/java core/src/test/java -name '*.java' -print0 | xargs -0 java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -d core/build/manual
java -cp core/build/manual ru.fridgedays.core.CoreTest
