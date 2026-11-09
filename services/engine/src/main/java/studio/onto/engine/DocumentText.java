package studio.onto.engine;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts plain text from the document formats a business owner sends with a
 * PRD or RFQ. PDF and DOCX are parsed locally; everything else is read as UTF-8.
 */
public final class DocumentText {

    private static final int LIMIT = 60_000;

    private DocumentText() {
    }

    public static String extract(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase();
        String text;
        if (name.endsWith(".pdf")) {
            try (PDDocument document = PDDocument.load(path.toFile())) {
                text = new PDFTextStripper().getText(document);
            }
        } else if (name.endsWith(".docx")) {
            try (var stream = Files.newInputStream(path);
                 var document = new XWPFDocument(stream);
                 var extractor = new XWPFWordExtractor(document)) {
                text = extractor.getText();
            }
        } else {
            text = Files.readString(path, StandardCharsets.UTF_8);
        }
        text = text.replace("\r\n", "\n").strip();
        return text.length() > LIMIT ? text.substring(0, LIMIT) + "\n[truncated]" : text;
    }
}
