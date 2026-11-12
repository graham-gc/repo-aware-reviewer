package reviewagent;

import java.io.IOException;

/** Valid protocol whose claimed conclusion lacks the required proof. */
final class ReviewDecisionException extends IOException {
    ReviewDecisionException(String message) { super(message); }
}
