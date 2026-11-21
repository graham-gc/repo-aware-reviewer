package reviewagent;

import com.thaiopensource.relaxng.jaxp.XMLSyntaxSchemaFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import java.io.IOException;
import java.io.StringReader;

/** RELAX NG gate for repaired response trees, using the shared protocol vocabulary. */
final class ReviewRepairValidation {
    private static final Schema SCHEMA = compileGrammar();

    static void validate(Element root) throws IOException {
        // The source is the strictly parsed tree after protocol normalization.
        try (var xml = new StringReader(ReviewXml.markup(root))) {
            SCHEMA.newValidator().validate(new StreamSource(xml));
        } catch (SAXException invalid) {
            throw new IOException("Repaired review response failed RELAX NG validation: " + invalid.getMessage(), invalid);
        }
    }

    private static Schema compileGrammar() {
        try (var grammar = new StringReader(ReviewSchema.repairGrammar())) {
            return new XMLSyntaxSchemaFactory().newSchema(new StreamSource(grammar));
        } catch (SAXException invalid) {
            throw new IllegalStateException("Invalid review response grammar", invalid);
        }
    }
}
