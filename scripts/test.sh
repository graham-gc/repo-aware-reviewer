#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source "$project_dir/scripts/progress.sh"
source "$project_dir/scripts/compile.sh"
source "$project_dir/scripts/dependencies.sh"
main_classes="$project_dir/build/test-main"
test_classes="$project_dir/build/test-classes"

mkdir -p "$main_classes" "$test_classes"
progress_step test 0 4 'Preparing test directories' find "$main_classes" "$test_classes" -type f -delete
prepare_java_dependencies "$project_dir/build/deps"

progress_step test 1 4 'Compiling Java sources' \
    compile_java_sources "$project_dir/src/main/java" -cp "$java_dependency_classpath" -d "$main_classes"
progress_step test 2 4 'Compiling tests' \
    compile_java_sources "$project_dir/src/test/java" -cp "$main_classes:$java_dependency_classpath" -d "$test_classes"
copy_runtime_resources "$project_dir/src/main/resources" "$main_classes"
progress_step test 3 4 'Running tests' java -cp "$main_classes:$test_classes:$java_dependency_classpath" reviewagent.AllTests
progress_line test 4 4 'Tests complete'

