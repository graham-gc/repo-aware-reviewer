#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source "$project_dir/scripts/progress.sh"
source "$project_dir/scripts/compile.sh"
source "$project_dir/scripts/dependencies.sh"
classes_dir="$project_dir/build/classes"
jar_path="$project_dir/build/code-review-agent.jar"

package_review_agent() {
    copy_runtime_resources "$project_dir/src/main/resources" "$classes_dir"
    bundle_java_dependencies "$classes_dir" || return 1
    jar --create --file "$jar_path" --main-class reviewagent.Main -C "$classes_dir" .
}

mkdir -p "$classes_dir"
progress_step build 0 3 'Preparing build directory' find "$classes_dir" -type f -delete
prepare_java_dependencies "$project_dir/build/deps"

progress_step build 1 3 'Compiling Java sources' \
    compile_java_sources "$project_dir/src/main/java" -cp "$java_dependency_classpath" -d "$classes_dir"
progress_step build 2 3 'Packaging the JAR' \
    package_review_agent

progress_line build 3 3 'JAR ready'
echo "$jar_path"

