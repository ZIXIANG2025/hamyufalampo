package com.safedocs.tokenization;

import com.safedocs.core.PiiEntity;
import com.safedocs.core.TokenizedDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TokenizationEngine {

    public TokenizedDocument tokenize(String sourceText, List<PiiEntity> entities) {
        if (sourceText == null || sourceText.isEmpty() || entities == null || entities.isEmpty()) {
            return new TokenizedDocument(sourceText == null ? "" : sourceText, Map.of(), 0);
        }

        Map<String, String> textToToken = new LinkedHashMap<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, Integer> counters = new LinkedHashMap<>();
        Map<EntityKey, String> tokenByEntity = new LinkedHashMap<>();

        for (PiiEntity entity : entities) {
            if (entity == null || entity.getMatchedText() == null || entity.getMatchedText().isBlank()) {
                continue;
            }
            String matchedText = entity.getMatchedText();
            if (!sourceText.contains(matchedText) || textToToken.containsKey(matchedText)) {
                continue;
            }

            String type = entity.getEntityType() == null ? "OTHER"
                    : entity.getEntityType().trim().toUpperCase(Locale.ROOT);
            EntityKey key = new EntityKey(type, matchedText);
            String token = tokenByEntity.get(key);
            if (token == null) {
                String category = tokenCategory(type);
                int number = counters.getOrDefault(category, 0) + 1;
                token = formatToken(category, number);
                while (sourceText.contains(token) || mapping.containsKey(token)) {
                    number++;
                    token = formatToken(category, number);
                }
                counters.put(category, number);
                tokenByEntity.put(key, token);
                mapping.put(token, matchedText);
            }
            textToToken.put(matchedText, token);
        }

        if (textToToken.isEmpty()) {
            return new TokenizedDocument(sourceText, Map.of(), 0);
        }

        List<String> alternatives = new ArrayList<>(textToToken.keySet());
        alternatives.sort(Comparator.comparingInt(String::length).reversed());
        Pattern matches = Pattern.compile(alternatives.stream().map(Pattern::quote)
                .reduce((left, right) -> left + "|" + right).orElseThrow());
        Matcher matcher = matches.matcher(sourceText);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(textToToken.get(matcher.group())));
        }
        matcher.appendTail(sanitized);
        return new TokenizedDocument(sanitized.toString(), Map.copyOf(mapping), mapping.size());
    }

    private String formatToken(String category, int number) {
        return "[" + category + "_" + String.format(Locale.ROOT, "%03d", number) + "]";
    }

    private String tokenCategory(String type) {
        return switch (type) {
            case "PERSON" -> "NAME";
            case "MYKAD", "IC" -> "IC";
            case "MATRIC", "STUDENT_ID" -> "MATRIC";
            case "GENDER" -> "GENDER";
            case "PASSPORT" -> "PASSPORT";
            case "BANK_ACCOUNT" -> "BANK_ACCOUNT";
            case "CREDIT_CARD" -> "CARD";
            case "TAX_ID" -> "TAX_ID";
            case "DATE_OF_BIRTH" -> "DOB";
            case "PHONE" -> "PHONE";
            case "EMAIL" -> "EMAIL";
            case "ADDRESS" -> "ADDRESS";
            case "FINANCIAL" -> "FINANCIAL";
            case "MEDICAL" -> "MEDICAL";
            case "SIGNATURE" -> "SIGNATURE";
            case "IDENTIFIER" -> "IDENTIFIER";
            default -> "SENSITIVE";
        };
    }

    private record EntityKey(String type, String text) {
    }
}
