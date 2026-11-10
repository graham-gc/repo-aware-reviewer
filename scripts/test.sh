#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
main_classes="$project_dir/build/test-main"
test_classes="$project_dir/build/test-classes"

mkdir -p "$main_classes" "$test_classes"
find "$main_classes" "$test_classes" -type f -delete

javac --release 21 -d "$main_classes" $(find "$project_dir/src/main/java" -name '*.java' -print)
javac --release 21 -cp "$main_classes" -d "$test_classes" $(find "$project_dir/src/test/java" -name '*.java' -print)
java -cp "$main_classes:$test_classes" reviewagent.AllTests

