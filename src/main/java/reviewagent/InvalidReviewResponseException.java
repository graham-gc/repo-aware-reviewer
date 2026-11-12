package reviewagent;

import java.io.IOException;

/** All bounded correction attempts for one response failed validation. */
final class InvalidReviewResponseException extends IOException {
    InvalidReviewResponseException(String message, IOException cause) { super(message, cause); }
}
