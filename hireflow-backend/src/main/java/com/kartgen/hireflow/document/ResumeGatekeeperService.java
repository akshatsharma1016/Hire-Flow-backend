package com.kartgen.hireflow.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeGatekeeperService {

    private final TikaMimeDetector mimeDetector;
    private final PdfBoxTextExtractor pdfExtractor;
    private final Tika tika = new Tika();

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{2,4}\\)?[-.\\s]?\\d{3,4}[-.\\s]?\\d{3,4}");

    public record GatekeeperResult(boolean accepted, String mimeType, String extractedText, String rejectionReason) {}

    public GatekeeperResult evaluate(byte[] fileData, String fileName) {
        String mime = mimeDetector.detectMimeType(fileData, fileName);
        if (!mimeDetector.isResumeMimeType(mime)) {
            return new GatekeeperResult(false, mime, null,
                    "Invalid file type: " + mime + ". Only PDF, DOCX, DOC accepted.");
        }

        String text = "";
        if (mime.contains("pdf")) {
            text = pdfExtractor.extractText(fileData);
        } else {
            // For DOCX, Tika can extract text; for simplicity use PDFBox for PDF only
            try {
                // ⚡ Bolt: Use shared thread-safe Tika instance to avoid expensive reinstantiation per request
                text = tika.parseToString(new java.io.ByteArrayInputStream(fileData));
            } catch (Exception e) {
                log.warn("Tika text extraction failed for {}: {}", fileName, e.getMessage());
            }
        }

        if (text == null || text.isBlank()) {
            return new GatekeeperResult(false, mime, text, "Could not extract text from file.");
        }

        if (!isLikelyResume(text)) {
            return new GatekeeperResult(false, mime, text,
                    "File does not appear to be a resume (insufficient resume signals).");
        }

        return new GatekeeperResult(true, mime, text, null);
    }

    public boolean isLikelyResume(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        int signals = 0;

        if (lower.contains("resume") || lower.contains("curriculum vitae") || lower.contains("cv"))
            signals += 2;
        if (lower.contains("experience") || lower.contains("work history") || lower.contains("employment") || lower.contains("career"))
            signals++;
        if (lower.contains("education") || lower.contains("university") || lower.contains("degree")
                || lower.contains("b.tech") || lower.contains("m.tech") || lower.contains("bachelor") || lower.contains("college"))
            signals++;
        if (lower.contains("skills") || lower.contains("technical skills") || lower.contains("proficient")
                || lower.contains("technologies") || lower.contains("competencies"))
            signals++;
        if (lower.contains("project") || lower.contains("projects"))
            signals++;
        if (lower.contains("developer") || lower.contains("engineer") || lower.contains("manager") || lower.contains("analyst") || lower.contains("architect"))
            signals++;
        if (lower.contains("linkedin") || lower.contains("github"))
            signals++;
        if (EMAIL_PATTERN.matcher(text).find())
            signals++;
        if (PHONE_PATTERN.matcher(text).find())
            signals++;

        return signals >= 2;
    }
}

