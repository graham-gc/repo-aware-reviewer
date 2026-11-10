package reviewagent;

import java.util.List;

record KnowledgeSnapshot(String commit, List<KnowledgeFile> files, List<String> chunks,
                         long includedBytes, long repositoryBytes) {
}

