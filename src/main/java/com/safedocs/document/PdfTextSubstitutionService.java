package com.safedocs.document;

import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfObject;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfResources;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.pdfcleanup.PdfCleanUpLocation;
import com.itextpdf.pdfcleanup.PdfCleaner;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces PDF text in place while retaining the source pages and their visual content. */
@Service
public class PdfTextSubstitutionService {
    private static final float CLEANUP_PADDING = 1.5f;
    private static final String TOKEN_LOCATION_INFO = "SafeDocsTokenLocationsV1";
    private final PdfTextLocator locator;

    public PdfTextSubstitutionService(PdfTextLocator locator) {
        this.locator = locator;
    }

    public byte[] substitute(byte[] sourcePdf, Map<String, String> substitutions) throws IOException {
        return substitute(sourcePdf, substitutions, true);
    }

    /**
     * Restore documents may contain only a subset of the saved tokens (for example, an AI report).
     * In that case replace every matching token and require at least one match overall.
     */
    public byte[] substitute(byte[] sourcePdf, Map<String, String> substitutions, boolean requireAllTargets) throws IOException {
        return substitute(sourcePdf, substitutions, requireAllTargets, null);
    }

    public byte[] substitute(byte[] sourcePdf, Map<String, String> substitutions,
                             boolean requireAllTargets, String documentId) throws IOException {
        if (sourcePdf == null || sourcePdf.length == 0) throw new IOException("The source PDF is empty.");
        if (substitutions == null || substitutions.isEmpty()) return sourcePdf;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(sourcePdf)), new PdfWriter(output))) {
            List<PdfTextSpan> spans = locator.locateAll(pdf);
            // Restoring from token PDFs uses the original font resource and point size captured
            // during tokenization. Fall back to text matching for older PDFs without metadata.
            List<Replacement> replacements = !requireAllTargets
                    ? locateFromStoredLocations(pdf, spans, substitutions)
                    : List.of();
            if (replacements.isEmpty()) {
                replacements = locateReplacements(spans, substitutions, requireAllTargets);
            }
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

            Map<String, PdfFont> fallbackFonts = new HashMap<>();
            for (Replacement replacement : replacements) drawReplacement(pdf, replacement, fallbackFonts);
            if (requireAllTargets && documentId != null && !documentId.isBlank()) {
                pdf.getDocumentInfo().setMoreInfo(TOKEN_LOCATION_INFO,
                        writeTokenLocations(pdf, documentId, replacements, fallbackFonts));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Could not preserve and substitute text in this PDF.", exception);
        }
        return output.toByteArray();
    }

    /** Finds token strings using the same low-level PDF text spans used by substitution. */
    public Set<String> findMatchingTargets(byte[] sourcePdf, Set<String> targets) throws IOException {
        if (sourcePdf == null || sourcePdf.length == 0 || targets == null || targets.isEmpty()) return Set.of();
        Map<String, String> identitySubstitutions = new LinkedHashMap<>();
        for (String target : targets) identitySubstitutions.put(target, target);
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(sourcePdf)))) {
            List<Replacement> matches = locateReplacements(locator.locateAll(pdf), identitySubstitutions, false);
            Set<String> found = new LinkedHashSet<>();
            for (Replacement match : matches) found.add(match.target());
            return found;
        } catch (RuntimeException exception) {
            throw new IOException("Could not inspect text in this PDF.", exception);
        }
    }

    public Optional<String> readTokenDocumentId(byte[] sourcePdf) throws IOException {
        if (sourcePdf == null || sourcePdf.length == 0) return Optional.empty();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(sourcePdf)))) {
            String info = pdf.getDocumentInfo().getMoreInfo(TOKEN_LOCATION_INFO);
            if (info == null || info.isBlank()) return Optional.empty();
            String firstLine = info.lines().findFirst().orElse("");
            return firstLine.startsWith("documentId=")
                    ? Optional.of(firstLine.substring("documentId=".length())) : Optional.empty();
        } catch (RuntimeException exception) {
            throw new IOException("Could not read SafeDocs metadata from this PDF.", exception);
        }
    }

    private List<Replacement> locateFromStoredLocations(PdfDocument pdf, List<PdfTextSpan> spans,
                                                         Map<String, String> substitutions) throws IOException {
        String info = pdf.getDocumentInfo().getMoreInfo(TOKEN_LOCATION_INFO);
        if (info == null || info.isBlank()) return List.of();
        List<Replacement> replacements = new ArrayList<>();
        for (String line : info.lines().skip(1).toList()) {
            String[] fields = line.split("\\|", -1);
            if (fields.length < 9 || fields.length > 12) continue;
            try {
                String token = new String(Base64.getUrlDecoder().decode(fields[0]), java.nio.charset.StandardCharsets.UTF_8);
                String original = substitutions.get(token);
                if (original == null || original.isEmpty()) continue;
                int page = Integer.parseInt(fields[1]);
                float x = Float.parseFloat(fields[2]);
                float y = Float.parseFloat(fields[3]);
                float a = Float.parseFloat(fields[4]);
                float b = Float.parseFloat(fields[5]);
                float length = Float.parseFloat(fields[6]);
                float fontSize = Float.parseFloat(fields[7]);
                boolean version2 = fields.length == 10 && "v2".equals(fields[8]);
                boolean version3 = fields.length == 11 && "v3".equals(fields[8]);
                boolean version4 = fields.length == 12 && "v4".equals(fields[8]);
                String fontResourceName = version2 || version3 || version4 ? decodeMetadataField(fields[9]) : null;
                String fontBaseName = version3 || version4 ? decodeMetadataField(fields[10]) : null;
                float originalWidth = version4 ? Float.parseFloat(fields[11]) : 0f;
                List<PdfTextSpan> occurrence = new ArrayList<>();
                for (PdfTextSpan span : spans) {
                    if (span.pageNumber() != page) continue;
                    float dx = span.baselineX() - x;
                    float dy = span.baselineY() - y;
                    float along = dx * a + dy * b;
                    float across = Math.abs(-dx * b + dy * a);
                    if (across <= Math.max(1.5f, fontSize * 0.3f) && along >= -1f && along <= length + 1f) {
                        occurrence.add(span);
                    }
                }
                if (!occurrence.isEmpty()) {
                    PdfFont originalFont = findStoredFont(pdf, page, fontResourceName, fontBaseName,
                            occurrence.get(0).font(), original);
                    if (originalFont != null) {
                        List<PdfTextSpan> restoredSpans = new ArrayList<>(occurrence.size());
                        for (PdfTextSpan span : occurrence) {
                            restoredSpans.add(new PdfTextSpan(span.pageNumber(), span.text(), span.rectangle(),
                                    span.baselineX(), span.baselineY(), span.baselineA(), span.baselineB(),
                                    originalFont, fontSize, span.color(), fontResourceName, fontBaseName));
                        }
                        occurrence = restoredSpans;
                    }
                    replacements.add(new Replacement(occurrence, token, original, fontSize, length, originalWidth));
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("SafeDocs token location metadata is damaged.", exception);
            }
        }
        return replacements;
    }

    private PdfFont findStoredFont(PdfDocument pdf, int pageNumber, String resourceName,
                                  String baseFontName, PdfFont tokenFont, String restoredText) {
        PdfResources resources = pdf.getPage(pageNumber).getResources();
        if (resourceName != null && !resourceName.isBlank()) {
            PdfObject fontObject = resources.getResourceObject(PdfName.Font, new PdfName(resourceName));
            if (fontObject instanceof PdfDictionary fontDictionary) {
                PdfName actualBaseName = fontDictionary.getAsName(PdfName.BaseFont);
                if (baseFontName == null || baseFontName.isBlank() || (actualBaseName != null
                        && normalizedFontName(actualBaseName.getValue()).equals(normalizedFontName(baseFontName)))) {
                    return PdfFontFactory.createFont(fontDictionary);
                }
            }
        }

        // PdfCleaner may rename resources (for example, F10 becomes F3). Match the
        // PostScript font name saved before cleanup instead of relying on that key.
        if (baseFontName != null && !baseFontName.isBlank()) {
            String expectedName = normalizedFontName(baseFontName);
            PdfFont firstMatchingFont = null;
            for (PdfName candidateName : resources.getResourceNames(PdfName.Font)) {
                PdfObject fontObject = resources.getResourceObject(PdfName.Font, candidateName);
                if (!(fontObject instanceof PdfDictionary fontDictionary)) continue;
                PdfFont candidate = PdfFontFactory.createFont(fontDictionary);
                PdfName candidateBaseName = fontDictionary.getAsName(PdfName.BaseFont);
                if (candidateBaseName == null || !normalizedFontName(candidateBaseName.getValue()).equals(expectedName)) {
                    continue;
                }
                if (firstMatchingFont == null) firstMatchingFont = candidate;
                if (restoredText.codePoints().allMatch(candidate::containsGlyph)) return candidate;
            }
            if (firstMatchingFont != null) return firstMatchingFont;
        }

        // Older metadata has no PostScript name. The token's own font generally keeps
        // the original family and style, or is the matching fallback chosen at tokenize time.
        return tokenFont;
    }

    private String normalizedFontName(String name) {
        return name.replaceFirst("^[A-Z]{6}\\+", "").toLowerCase(Locale.ROOT);
    }

    private String decodeMetadataField(String field) {
        return new String(Base64.getUrlDecoder().decode(field), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String writeTokenLocations(PdfDocument pdf, String documentId, List<Replacement> replacements,
                                       Map<String, PdfFont> fallbackFonts) throws IOException {
        StringBuilder info = new StringBuilder("documentId=").append(documentId);
        for (Replacement replacement : replacements) {
            PdfTextSpan first = replacement.spans().get(0);
            float originalWidth = replacement.originalWidth();
            float projectedLength = originalWidth;
            float size = Math.max(4f, first.fontSize());
            PdfFont textFont = outputFont(pdf, first.font(), replacement.text(), fallbackFonts);
            if (textFont != null) {
                float tokenWidth = textFont.getWidth(replacement.text(), size);
                if (tokenWidth > projectedLength && tokenWidth > 0) {
                    tokenWidth *= Math.max(0.55f, projectedLength / tokenWidth);
                }
                projectedLength = Math.max(projectedLength, tokenWidth);
            }
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(replacement.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            info.append('\n').append(token).append('|').append(first.pageNumber())
                    .append('|').append(first.baselineX()).append('|').append(first.baselineY())
                    .append('|').append(first.baselineA()).append('|').append(first.baselineB())
                    .append('|').append(projectedLength).append('|').append(first.fontSize()).append('|').append("v4")
                    .append('|').append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                            (first.fontResourceName() == null ? "" : first.fontResourceName())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .append('|').append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                            (first.fontBaseName() == null ? "" : first.fontBaseName())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .append('|').append(originalWidth);
        }
        return info.toString();
    }

    private List<Replacement> locateReplacements(List<PdfTextSpan> sourceSpans,
                                                  Map<String, String> substitutions,
                                                  boolean requireAllTargets) throws IOException {
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
            int fullyCovered = 0;
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
                List<int[]> matches = findMatches(content.toString(), target, entry.getKey());
                for (int[] match : matches) {
                    int found = match[0];
                    int after = match[1];
                    List<PdfTextSpan> occurrence = new ArrayList<>();
                    Set<PdfTextSpan> local = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                    boolean overlap = false;
                    for (int c = found; c < after; c++) {
                        PdfTextSpan span = charMap.get(c);
                        if (used.contains(span)) overlap = true;
                        if (local.add(span)) occurrence.add(span);
                    }
                    if (!occurrence.isEmpty()) {
                        long alreadyReplaced = occurrence.stream().filter(used::contains).count();
                        if (alreadyReplaced == 0) {
                            replacements.add(new Replacement(occurrence, entry.getKey(), entry.getValue(), 0f,
                                    0f, projectedWidth(occurrence)));
                            used.addAll(occurrence);
                            located++;
                        } else if (alreadyReplaced == occurrence.size()) {
                            // This value is fully contained in a longer value already tokenized.
                            fullyCovered++;
                        } else if (overlap) {
                            throw new IOException("Sensitive text spans overlap and cannot be replaced safely.");
                        }
                    }
                }
                start = end;
            }
            if (requireAllTargets && located == 0 && fullyCovered == 0) {
                throw new IOException("One or more requested text spans could not be located safely.");
            }
        }
        return replacements;
    }

    /**
     * Some subset PDF fonts render punctuation correctly but expose it as U+FFFD
     * through their ToUnicode map. Try exact text first, then tolerate damaged
     * punctuation only for our strictly formatted token keys.
     */
    private List<int[]> findMatches(String content, String normalizedTarget, String originalTarget) {
        List<int[]> matches = new ArrayList<>();
        int cursor = 0;
        while (cursor < content.length()) {
            int found = content.indexOf(normalizedTarget, cursor);
            if (found < 0) break;
            matches.add(new int[]{found, found + normalizedTarget.length()});
            cursor = found + normalizedTarget.length();
        }
        if (!isToken(originalTarget)) return matches;

        Matcher parts = Pattern.compile("[A-Z0-9]+").matcher(originalTarget);
        StringBuilder tolerant = new StringBuilder("(?<![\\p{L}\\p{N}])[^\\p{L}\\p{N}]{0,4}");
        boolean first = true;
        while (parts.find()) {
            if (!first) tolerant.append("[^\\p{L}\\p{N}]{0,4}");
            tolerant.append(Pattern.quote(parts.group().toLowerCase(Locale.ROOT)));
            first = false;
        }
        tolerant.append("[^\\p{L}\\p{N}]{0,4}(?![\\p{L}\\p{N}])");
        Matcher matcher = Pattern.compile(tolerant.toString()).matcher(content);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (matches.stream().noneMatch(match -> match[0] == start && match[1] == end)) {
                matches.add(new int[]{start, end});
            }
        }
        matches.sort(Comparator.comparingInt(match -> match[0]));
        return matches;
    }

    private void drawReplacement(PdfDocument pdf, Replacement replacement,
                                 Map<String, PdfFont> fallbackFonts) throws IOException {
        List<PdfTextSpan> spans = replacement.spans();
        PdfTextSpan first = spans.get(0);
        Rectangle bounds = boundsOf(spans);
        float left = bounds.getLeft();
        float right = bounds.getRight();
        float availableWidth = Math.max(2f, right - left);
        float originalSize = Math.max(4f, replacement.fontSize() > 0 ? replacement.fontSize() : first.fontSize());
        float horizontalScale = 1f;
        boolean isToken = isToken(replacement.text());
        PdfFont outputFont = outputFont(pdf, first.font(), replacement.text(), fallbackFonts);
        if (outputFont != null) {
            float replacementWidth = outputFont.getWidth(replacement.text(), originalSize);
            // Preserve the original PDF's horizontal text scaling. Source documents often
            // compress field values independently of their point size (for example, Tz=74%).
            if (!isToken && replacement.originalWidth() > 0 && replacementWidth > 0) {
                horizontalScale = replacement.originalWidth() / replacementWidth;
            } else if (!isToken && replacement.widthLimit() > 0 && replacementWidth > replacement.widthLimit()) {
                // Older location metadata only has the token's maximum occupied width.
                horizontalScale = replacement.widthLimit() / replacementWidth;
            } else if (isToken && replacementWidth > availableWidth && replacementWidth > 0) {
                // Keep newly generated tokens inside the replaced field.
                horizontalScale = Math.max(0.55f, availableWidth / replacementWidth);
            }
        }
        PdfCanvas canvas = new PdfCanvas(pdf.getPage(first.pageNumber()).newContentStreamAfter(),
                pdf.getPage(first.pageNumber()).getResources(), pdf);
        canvas.saveState().beginText();
        if (outputFont != null) canvas.setFontAndSize(outputFont, originalSize);
        if (horizontalScale < 1f) canvas.setHorizontalScaling(horizontalScale * 100f);
        if (first.color() != null) canvas.setFillColor(first.color());
        float a = first.baselineA();
        float b = first.baselineB();
        canvas.setTextMatrix(a, b, -b, a, first.baselineX(), first.baselineY()).showText(replacement.text());
        canvas.endText().restoreState();
    }

    /**
     * Reuse the source font whenever it can encode every replacement character. PDF fonts are
     * often subsetted, though, so they may lack token punctuation such as '[', ']' or '_'.
     * Writing those through the subset font can produce missing or substituted glyphs.
     */
    private PdfFont outputFont(PdfDocument pdf, PdfFont sourceFont, String text,
                               Map<String, PdfFont> fallbackFonts) throws IOException {
        if (sourceFont != null && text.codePoints().allMatch(sourceFont::containsGlyph)) return sourceFont;

        String sourceName = "";
        if (sourceFont != null) {
            PdfName baseFont = sourceFont.getPdfObject().getAsName(PdfName.BaseFont);
            if (baseFont != null) sourceName = baseFont.getValue();
        }
        String normalizedName = sourceName.replaceFirst("^[A-Z]{6}\\+", "").toLowerCase(Locale.ROOT);
        boolean bold = normalizedName.contains("bold") || normalizedName.contains("black")
                || normalizedName.contains("heavy");
        boolean italic = normalizedName.contains("italic") || normalizedName.contains("oblique");
        String family;
        String stem;
        if (normalizedName.contains("courier") || normalizedName.contains("mono")) {
            family = "cour";
            stem = "Courier New";
        } else if (normalizedName.contains("times") || normalizedName.contains("serif")
                || normalizedName.contains("cambria") || normalizedName.contains("georgia")
                || normalizedName.contains("palatino") || normalizedName.contains("garamond")) {
            family = "times";
            stem = "Times New Roman";
        } else {
            family = "arial";
            stem = "Arial";
        }
        String style = bold && italic ? "bi" : bold ? "bd" : italic ? "i" : "";
        String styleFile = family + style + ".ttf";
        Path fontPath = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts", styleFile);
        if (!Files.isRegularFile(fontPath)) {
            throw new IOException("The source PDF font cannot encode the replacement text, and the matching "
                    + stem + " fallback font is unavailable.");
        }
        String cacheKey = fontPath.toString();
        PdfFont fallback = fallbackFonts.get(cacheKey);
        if (fallback == null) {
            fallback = PdfFontFactory.createFont(fontPath.toString(), PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED, pdf);
            fallbackFonts.put(cacheKey, fallback);
        }
        if (!text.codePoints().allMatch(fallback::containsGlyph)) {
            throw new IOException("Neither the source PDF font nor the matching fallback font can encode the replacement text.");
        }
        return fallback;
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

    private float projectedWidth(List<PdfTextSpan> spans) {
        PdfTextSpan first = spans.get(0);
        Rectangle bounds = boundsOf(spans);
        return bounds.getWidth() * Math.abs(first.baselineA())
                + bounds.getHeight() * Math.abs(first.baselineB());
    }

    private String normalize(String value) {
        String compatible = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(compatible.length());
        compatible.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
                        && !Character.isSpaceChar(codePoint)
                        && Character.getType(codePoint) != Character.FORMAT)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private boolean isToken(String value) {
        return value != null && value.matches("\\[[A-Z][A-Z0-9_]*_\\d{3,}]");
    }

    private record Replacement(List<PdfTextSpan> spans, String target, String text,
                               float fontSize, float widthLimit, float originalWidth) { }
}
