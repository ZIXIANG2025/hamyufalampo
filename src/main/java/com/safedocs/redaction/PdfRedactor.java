package com.safedocs.redaction;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.pdfcleanup.PdfCleanUpLocation;
import com.itextpdf.pdfcleanup.PdfCleaner;
import com.safedocs.core.PiiEntity;
import com.safedocs.document.PdfTextLocator;
import com.safedocs.document.PdfTextSpan;
import com.safedocs.detection.Policies;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfRedactor {
    private static final float PADDING = 1.5f;

    private final PdfTextLocator textLocator;

    public PdfRedactor(PdfTextLocator textLocator) {
        this.textLocator = textLocator;
    }

    public byte[] applyRedaction(byte[] fileBytes, List<Policies> policies, List<PiiEntity> entities) throws IOException {
        if (policies == null || !policies.contains(Policies.AI_PII)) {
            throw new IllegalArgumentException("AI_PII is required for AI-based PDF redaction.");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(
                new PdfReader(new ByteArrayInputStream(fileBytes)), new PdfWriter(output))) {
            MetadataSanitizer.clean(pdf);

            List<PdfCleanUpLocation> locations = new ArrayList<>();
            List<PdfTextSpan> sourceSpans = textLocator.locateAll(pdf);
            for (PiiEntity entity : entities) {
                if (entity.getMatchedText() == null || entity.getMatchedText().isBlank()) {
                    continue;
                }
                List<PdfTextSpan> matching = locateExactMatches(sourceSpans, entity.getMatchedText());
                if (matching.isEmpty()) {
                    throw new IOException("AI returned text that could not be mapped exactly to the PDF. No output file was returned.");
                }
                for (PdfTextSpan span : matching) {
                    Rectangle rectangle = span.rectangle();
                    Rectangle bounds = new Rectangle(
                            rectangle.getLeft() - PADDING,
                            rectangle.getBottom() - PADDING,
                            rectangle.getWidth() + PADDING * 2,
                            rectangle.getHeight() + PADDING * 2);
                    locations.add(new PdfCleanUpLocation(span.pageNumber(), bounds, ColorConstants.BLACK));
                }
            }

            if (!entities.isEmpty() && locations.isEmpty()) {
                throw new IOException("AI detected personal information, but its exact PDF text locations could not be verified. No file was returned.");
            }
            if (entities.isEmpty()) {
                throw new IOException("The AI model returned no redaction candidates; refusing to return an unverified original document.");
            }
            PdfCleaner.cleanUp(pdf, locations);
        }
        return output.toByteArray();
    }

    private List<PdfTextSpan> locateExactMatches(List<PdfTextSpan> sourceSpans, String target) {
        List<PdfTextSpan> matches = new ArrayList<>();
        String normalizedTarget = normalize(target);
        List<String> normalizedCharacters = new ArrayList<>();
        List<PdfTextSpan> characters = new ArrayList<>();
        for (PdfTextSpan span : sourceSpans) {
            String normalized = normalize(span.text());
            for (int offset = 0; offset < normalized.length(); ) {
                int codePoint = normalized.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                normalizedCharacters.add(character);
                characters.add(span);
                offset += Character.charCount(codePoint);
            }
        }

        StringBuilder text = new StringBuilder();
        normalizedCharacters.forEach(text::append);
        for (int start = 0; start < characters.size(); ) {
            int pageNumber = characters.get(start).pageNumber();
            int pageEnd = start + 1;
            while (pageEnd < characters.size() && characters.get(pageEnd).pageNumber() == pageNumber) {
                pageEnd++;
            }
            String pageText = text.substring(start, pageEnd);
            int matchStart = 0;
            while (matchStart < pageText.length()) {
                int occurrence = pageText.indexOf(normalizedTarget, matchStart);
                if (occurrence < 0) {
                    break;
                }
                int matchEnd = occurrence + normalizedTarget.length();
                for (int i = occurrence; i < matchEnd; i++) {
                    matches.add(characters.get(start + i));
                }
                matchStart = matchEnd;
            }
            start = pageEnd;
        }
        return matches;
    }

    private String normalize(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
