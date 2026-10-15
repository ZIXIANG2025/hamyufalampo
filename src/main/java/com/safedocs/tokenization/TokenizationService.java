package com.safedocs.tokenization;

import com.safedocs.core.PiiEntity;
import com.safedocs.detection.Policies;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/** Stateless: dictionaries are created per request and never retained in a shared service/session. */
@Service
public class TokenizationService {
    private static final Pattern TOKEN = Pattern.compile("\\[[A-Z][A-Z0-9_]*_[0-9]{3,}\\]");
    private static final Set<String> TYPES = new HashSet<>();
    private static final int MAX_MATCHES = 100_000;

    static {
        for (Policies policy : Policies.values()) TYPES.addAll(policy.entityTypes());
    }

    public TokenizationResult tokenize(String text, List<PiiEntity> entities) {
        Objects.requireNonNull(text, "Document text is required.");
        Objects.requireNonNull(entities, "Detection results are required.");
        // Prefer the more specific, longer value when detection returns contained spans.
        List<Span> matches = new ArrayList<>();
        Set<String> seenValues = new HashSet<>();
        for (PiiEntity entity : entities) {
            if (entity == null || !TYPES.contains(entity.entityType())
                    || entity.matchedText() == null || entity.matchedText().isBlank()) {
                throw new IllegalArgumentException("The detector returned an invalid entity.");
            }
            String value = entity.matchedText();
            if (!text.contains(value)) {
                throw new IllegalArgumentException("The detector returned text absent from the document.");
            }
            // Exact repeated values always share a token, including inconsistent model classifications.
            if (!seenValues.add(value)) continue;
            int previousSize = matches.size();
            for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
                int end = at + value.length();
                if (hasBoundary(text, at, end)) {
                    matches.add(new Span(at, end, entity.entityType()));
                    if (matches.size() > MAX_MATCHES) {
                        throw new IllegalArgumentException("Too many sensitive matches. Split the document into smaller PDFs.");
                    }
                }
            }
            if (matches.size() == previousSize) {
                throw new IllegalArgumentException("A detected value has no complete text occurrence. No output was returned.");
            }
        }
        matches.sort(Comparator.comparingInt(Span::start)
                .thenComparing(Comparator.comparingInt(Span::end).reversed()));
        List<Span> spans = new ArrayList<>();
        for (Span next : matches) {
            if (spans.isEmpty() || next.start() >= spans.get(spans.size() - 1).end()) {
                spans.add(next);
            } else {
                Span previous = spans.get(spans.size() - 1);
                if (next.end() > previous.end()) {
                    // Cover the whole intersecting region; never expose the tail of a sensitive span.
                    spans.set(spans.size() - 1, new Span(previous.start(), next.end(), "OTHER"));
                }
            }
        }

        Set<String> reserved = new HashSet<>();
        TOKEN.matcher(text).results().forEach(match -> reserved.add(match.group()));
        Map<String, Integer> counters = new HashMap<>();
        Map<String, String> valueTokens = new HashMap<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        StringBuilder sanitized = new StringBuilder();
        int cursor = 0;
        for (Span span : spans) {
            String value = text.substring(span.start(), span.end());
            String token = valueTokens.get(value);
            if (token == null) {
                do {
                    int number = counters.merge(span.type(), 1, Integer::sum);
                    token = "[" + span.type() + "_" + String.format(Locale.ROOT, "%03d", number) + "]";
                } while (reserved.contains(token));
                reserved.add(token);
                valueTokens.put(value, token);
                mapping.put(token, value);
                if (mapping.size() > 10_000) throw new IllegalArgumentException("Too many unique entities. Split the PDF.");
            }
            sanitized.append(text, cursor, span.start()).append(token);
            cursor = span.end();
        }
        sanitized.append(text, cursor, text.length());
        return new TokenizationResult(sanitized.toString(), mapping, spans.size());
    }

    private boolean hasBoundary(String text, int start, int end) {
        return !(start > 0 && isWord(text.codePointAt(start)) && isWord(text.codePointBefore(start)))
                && !(end < text.length() && isWord(text.codePointBefore(end)) && isWord(text.codePointAt(end)));
    }

    private boolean isWord(int point) {
        int type = Character.getType(point);
        return Character.isLetterOrDigit(point) || point == '_'
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private record Span(int start, int end, String type) { }
}
