package reviewagent;

record KnowledgeFile(String path, long size, Status status, String reason) {
    enum Status { INCLUDED, SKIPPED_BINARY_OR_GENERATED, SKIPPED_TOO_LARGE, SKIPPED_NON_REGULAR }
}

