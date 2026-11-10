#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source "$project_dir/scripts/progress.sh"
main_classes="$project_dir/build/test-main"
test_classes="$project_dir/build/test-classes"

mkdir -p "$main_classes" "$test_classes"
progress_step test 0 4 'Preparing test directories' find "$main_classes" "$test_classes" -type f -delete

progress_step test 1 4 'Compiling Java sources' \
    javac --release 21 -d "$main_classes" $(find "$project_dir/src/main/java" -name '*.java' -print)
progress_step test 2 4 'Compiling tests' \
    javac --release 21 -cp "$main_classes" -d "$test_classes" $(find "$project_dir/src/test/java" -name '*.java' -print)
progress_step test 3 4 'Running tests' java -cp "$main_classes:$test_classes" reviewagent.AllTests
progress_line test 4 4 'Tests complete'

