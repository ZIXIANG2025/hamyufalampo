package com.safedocs.redaction;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.xmp.XMPException;
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
        List<String> sourceInfoKeys = sourceInfoKeys(fileBytes);
        try (PdfDocument pdf = new PdfDocument(
                new PdfReader(new ByteArrayInputStream(fileBytes)), new PdfWriter(output))) {
            pdf.setFlushUnusedObjects(true);
            try {
                MetadataSanitizer.clean(pdf, sourceInfoKeys);
            } catch (XMPException exception) {
                throw new IOException("PDF metadata could not be sanitized. No output file was returned.", exception);
            }

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

    private List<String> sourceInfoKeys(byte[] fileBytes) throws IOException {
        try (PdfDocument source = new PdfDocument(new PdfReader(new ByteArrayInputStream(fileBytes)))) {
            PdfDictionary info = source.getTrailer().getAsDictionary(PdfName.Info);
            return info == null ? List.of() : info.keySet().stream().map(PdfName::getValue).toList();
        }
    }

    List<PdfTextSpan> locateExactMatches(List<PdfTextSpan> sourceSpans, String target) {
        List<PdfTextSpan> matches = new ArrayList<>();
        String normalizedTarget = normalize(target);
        if (normalizedTarget.isEmpty()) {
            return matches;
        }

        for (int start = 0; start < sourceSpans.size(); ) {
            int pageNumber = sourceSpans.get(start).pageNumber();
            StringBuilder pageText = new StringBuilder();
            List<PdfTextSpan> characterSpans = new ArrayList<>();
            int pageEnd = start;
            while (pageEnd < sourceSpans.size() && sourceSpans.get(pageEnd).pageNumber() == pageNumber) {
                PdfTextSpan span = sourceSpans.get(pageEnd++);
                String normalized = normalize(span.text());
                pageText.append(normalized);
                // String.indexOf uses UTF-16 offsets, so each code unit needs a matching span.
                for (int i = 0; i < normalized.length(); i++) {
                    characterSpans.add(span);
                }
            }
            int matchStart = 0;
            while (matchStart < pageText.length()) {
                int occurrence = pageText.indexOf(normalizedTarget, matchStart);
                if (occurrence < 0) {
                    break;
                }
                int matchEnd = occurrence + normalizedTarget.length();
                for (int i = occurrence; i < matchEnd; i++) {
                    PdfTextSpan span = characterSpans.get(i);
                    if (i == occurrence || span != characterSpans.get(i - 1)) {
                        matches.add(span);
                    }
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
