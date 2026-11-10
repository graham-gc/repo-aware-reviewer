#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source "$project_dir/scripts/progress.sh"
classes_dir="$project_dir/build/classes"
jar_path="$project_dir/build/code-review-agent.jar"

mkdir -p "$classes_dir"
progress_step build 0 3 'Preparing build directory' find "$classes_dir" -type f -delete

progress_step build 1 3 'Compiling Java sources' \
    javac --release 21 -d "$classes_dir" $(find "$project_dir/src/main/java" -name '*.java' -print)
progress_step build 2 3 'Packaging the JAR' \
    jar --create --file "$jar_path" --main-class reviewagent.Main -C "$classes_dir" .

progress_line build 3 3 'JAR ready'
echo "$jar_path"

