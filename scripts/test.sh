#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source "$project_dir/scripts/progress.sh"
source "$project_dir/scripts/compile.sh"
main_classes="$project_dir/build/test-main"
test_classes="$project_dir/build/test-classes"

mkdir -p "$main_classes" "$test_classes"
progress_step test 0 4 'Preparing test directories' find "$main_classes" "$test_classes" -type f -delete

progress_step test 1 4 'Compiling Java sources' \
    compile_java_sources "$project_dir/src/main/java" -d "$main_classes"
progress_step test 2 4 'Compiling tests' \
    compile_java_sources "$project_dir/src/test/java" -cp "$main_classes" -d "$test_classes"
copy_runtime_resources "$project_dir/src/main/resources" "$main_classes"
progress_step test 3 4 'Running tests' java -cp "$main_classes:$test_classes" reviewagent.AllTests
progress_line test 4 4 'Tests complete'

