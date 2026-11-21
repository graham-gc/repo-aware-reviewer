java_dependency_matches() {
    local archive=$1 checksum=$2 digest
    if command -v sha256sum >/dev/null; then
        digest=$(sha256sum < "$archive") || return 1
    else
        digest=$(shasum -a 256 < "$archive") || return 1
    fi
    [[ ${digest%% *} == "$checksum" ]]
}

fetch_java_dependency() {
    local archive=$1 coordinates=$2 checksum=$3 temporary
    if [[ -f "$archive" ]] && java_dependency_matches "$archive" "$checksum"; then
        return
    fi

    temporary=$(mktemp "$archive.XXXXXX") || return 1
    if ! curl --fail --silent --show-error --location --retry 2 \
        "https://repo.maven.apache.org/maven2/$coordinates" -o "$temporary"; then
        printf 'Retrying %s via the Maven Central mirror\n' "$coordinates" >&2
        if ! curl --fail --silent --show-error --location --retry 2 \
            "https://maven-central.storage-download.googleapis.com/maven2/$coordinates" -o "$temporary"; then
            rm -f "$temporary"
            return 1
        fi
    fi
    if ! java_dependency_matches "$temporary" "$checksum"; then
        printf 'SHA-256 verification failed for %s\n' "$coordinates" >&2
        rm -f "$temporary"
        return 1
    fi
    mv -f "$temporary" "$archive"
}

prepare_java_dependencies() {
    local cache=$1
    jsoup_jar="$cache/jsoup-1.24.1.jar"
    jing_jar="$cache/jing-20241231.jar"

    mkdir -p "$cache" || return 1
    fetch_java_dependency "$jsoup_jar" org/jsoup/jsoup/1.24.1/jsoup-1.24.1.jar \
        fabbb4aaf7fb5340aed0613826ecd110427999d765d8e0367cedde2950d7097c || return 1
    fetch_java_dependency "$jing_jar" org/relaxng/jing/20241231/jing-20241231.jar \
        ea5e9026244d977e607d8b52212d6871498ece51939f9c49d0e7a77aad91133a || return 1
    java_dependency_classpath="$jsoup_jar:$jing_jar"
}

bundle_java_dependencies() (
    cd "$1" || exit 1
    # Bundle the parser/validator packages and the jsoup license. Main keeps its own manifest.
    jar --extract --file "$jsoup_jar" org/jsoup META-INF/jsoup/LICENSE || exit 1
    jar --extract --file "$jing_jar" com/thaiopensource org/relaxng
)
