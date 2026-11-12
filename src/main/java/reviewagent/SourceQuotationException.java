package reviewagent;

import java.io.IOException;

/** A response cannot ground a quotation as literal text from the supplied source. */
final class SourceQuotationException extends IOException {
    SourceQuotationException(String message) {
        super(message);
    }
}
