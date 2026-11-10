package reviewagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

record InputDocument(Path path, String sha256, String content) {
    static InputDocument read(Path path) throws IOException {
        if (path == null) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(path);
        return new InputDocument(path, sha256(bytes), new String(bytes, StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

