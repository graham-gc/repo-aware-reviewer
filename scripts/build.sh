#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
classes_dir="$project_dir/build/classes"
jar_path="$project_dir/build/code-review-agent.jar"

mkdir -p "$classes_dir"
find "$classes_dir" -type f -delete

javac --release 21 -d "$classes_dir" $(find "$project_dir/src/main/java" -name '*.java' -print)
jar --create --file "$jar_path" --main-class reviewagent.Main -C "$classes_dir" .

echo "$jar_path"

