package reviewagent;

record ReviewScope(Mode mode, String requestedBase, String requestedTarget,
                   String baseCommit, String targetCommit, String diffExpression) {
    enum Mode { COMMIT, BRANCH }
}

