package reviewagent;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

record CommandResult(int exitCode, byte[] stdoutBytes, String stderr) {
    String stdout() {
        return new String(stdoutBytes, StandardCharsets.UTF_8);
    }

    String strictUtf8Stdout() throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stdoutBytes)).toString();
    }
}

