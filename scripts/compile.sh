compile_java_sources() {
    local source_root=$1 source_file
    shift
    local -a sources=()
    while IFS= read -r -d '' source_file; do
        sources+=("$source_file")
    done < <(find "$source_root" -type f -name '*.java' -print0)

    if [[ -z ${sources[0]:-} ]]; then
        printf 'No Java sources in %s\n' "$source_root" >&2
        return 1
    fi
    javac --release 21 "$@" "${sources[@]}"
}

copy_runtime_resources() {
    local resources_dir=$1 destination_dir=$2
    if [[ -d "$resources_dir" ]]; then
        cp -R "$resources_dir/." "$destination_dir/"
    fi
}
