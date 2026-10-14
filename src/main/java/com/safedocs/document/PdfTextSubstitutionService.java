package com.safedocs.document;

import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.pdfcleanup.PdfCleanUpLocation;
import com.itextpdf.pdfcleanup.PdfCleaner;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Replaces PDF text in place while retaining the source pages and their visual content. */
@Service
public class PdfTextSubstitutionService {
    private static final float CLEANUP_PADDING = 1.5f;
    private final PdfTextLocator locator;

    public PdfTextSubstitutionService(PdfTextLocator locator) {
        this.locator = locator;
    }

    public byte[] substitute(byte[] sourcePdf, Map<String, String> substitutions) throws IOException {
        if (sourcePdf == null || sourcePdf.length == 0) throw new IOException("The source PDF is empty.");
        if (substitutions == null || substitutions.isEmpty()) return sourcePdf;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(sourcePdf)), new PdfWriter(output))) {
            List<PdfTextSpan> spans = locator.locateAll(pdf);
            List<Replacement> replacements = locateReplacements(spans, substitutions);
            if (replacements.isEmpty()) throw new IOException("No replacement text could be located in the PDF.");

            List<PdfCleanUpLocation> cleanups = new ArrayList<>();
            for (Replacement replacement : replacements) {
                Rectangle bounds = boundsOf(replacement.spans());
                Rectangle cover = new Rectangle(bounds.getLeft() - CLEANUP_PADDING,
                        bounds.getBottom() - CLEANUP_PADDING,
                        bounds.getWidth() + CLEANUP_PADDING * 2,
                        bounds.getHeight() + CLEANUP_PADDING * 2);
                // The source text must be visually removed before the token is drawn.
                // White matches the document's form fields and prevents residual glyphs from showing through.
                cleanups.add(new PdfCleanUpLocation(replacement.spans().get(0).pageNumber(),
                        cover, ColorConstants.WHITE));
            }
            PdfCleaner.cleanUp(pdf, cleanups);

            for (Replacement replacement : replacements) drawReplacement(pdf, replacement);
        } catch (RuntimeException exception) {
            throw new IOException("Could not preserve and substitute text in this PDF.", exception);
        }
        return output.toByteArray();
    }

    private List<Replacement> locateReplacements(List<PdfTextSpan> sourceSpans,
                                                  Map<String, String> substitutions) throws IOException {
        List<Map.Entry<String, String>> ordered = substitutions.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isEmpty()
                        && entry.getValue() != null && !entry.getValue().isEmpty())
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> normalize(entry.getKey()).length()).reversed())
                .toList();
        Set<PdfTextSpan> used = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<Replacement> replacements = new ArrayList<>();
        for (Map.Entry<String, String> entry : ordered) {
            String target = normalize(entry.getKey());
            if (target.isEmpty()) continue;
            int located = 0;
            for (int start = 0; start < sourceSpans.size();) {
                int page = sourceSpans.get(start).pageNumber();
                int end = start;
                while (end < sourceSpans.size() && sourceSpans.get(end).pageNumber() == page) end++;
                StringBuilder content = new StringBuilder();
                List<PdfTextSpan> charMap = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    PdfTextSpan span = sourceSpans.get(i);
                    String normalized = normalize(span.text());
                    content.append(normalized);
                    for (int c = 0; c < normalized.length(); c++) charMap.add(span);
                }
                int cursor = 0;
                while (cursor < content.length()) {
                    int found = content.indexOf(target, cursor);
                    if (found < 0) break;
                    int after = found + target.length();
                    List<PdfTextSpan> occurrence = new ArrayList<>();
                    Set<PdfTextSpan> local = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                    boolean overlap = false;
                    for (int c = found; c < after; c++) {
                        PdfTextSpan span = charMap.get(c);
                        if (used.contains(span)) overlap = true;
                        if (local.add(span)) occurrence.add(span);
                    }
                    if (!overlap && !occurrence.isEmpty()) {
                        replacements.add(new Replacement(occurrence, entry.getValue()));
                        used.addAll(occurrence);
                        located++;
                    }
                    cursor = after;
                }
                start = end;
            }
            if (located == 0) throw new IOException("Could not locate a sensitive value or token in the PDF: " + safeLabel(entry.getKey()));
        }
        return replacements;
    }

    private void drawReplacement(PdfDocument pdf, Replacement replacement) {
        List<PdfTextSpan> spans = replacement.spans();
        PdfTextSpan first = spans.get(0);
        Rectangle bounds = boundsOf(spans);
        float left = bounds.getLeft();
        float right = bounds.getRight();
        float availableWidth = Math.max(2f, right - left);
        float originalSize = Math.max(4f, first.fontSize());
        float horizontalScale = 1f;
        if (first.font() != null) {
            float replacementWidth = first.font().getWidth(replacement.text(), originalSize);
            // Keep the source font size in the PDF text state. This lets decryption
            // restore the original value at its original size after a token was narrowed.
            if (replacement.text().matches("\\[[A-Z][A-Z0-9]*_\\d{3,}]" )
                    && replacementWidth > availableWidth && replacementWidth > 0) {
                horizontalScale = Math.max(0.55f, availableWidth / replacementWidth);
            }
        }
        PdfCanvas canvas = new PdfCanvas(pdf.getPage(first.pageNumber()).newContentStreamAfter(),
                pdf.getPage(first.pageNumber()).getResources(), pdf);
        canvas.saveState().beginText();
        if (first.font() != null) canvas.setFontAndSize(first.font(), originalSize);
        if (first.color() != null) canvas.setFillColor(first.color());
        canvas.setHorizontalScaling(horizontalScale * 100f);
        float a = first.baselineA();
        float b = first.baselineB();
        canvas.setTextMatrix(a, b, -b, a, first.baselineX(), first.baselineY()).showText(replacement.text());
        canvas.endText().restoreState();
    }

    private Rectangle boundsOf(List<PdfTextSpan> spans) {
        float left = Float.MAX_VALUE;
        float bottom = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float top = -Float.MAX_VALUE;
        for (PdfTextSpan span : spans) {
            Rectangle rectangle = span.rectangle();
            left = Math.min(left, rectangle.getLeft());
            bottom = Math.min(bottom, rectangle.getBottom());
            right = Math.max(right, rectangle.getRight());
            top = Math.max(top, rectangle.getTop());
        }
        return new Rectangle(left, bottom, Math.max(0.1f, right - left), Math.max(0.1f, top - bottom));
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String safeLabel(String value) {
        return value.length() > 24 ? value.substring(0, 24) + "…" : value;
    }

    private record Replacement(List<PdfTextSpan> spans, String text) { }
}
