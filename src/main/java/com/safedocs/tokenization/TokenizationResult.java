package com.safedocs.tokenization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TokenizationResult(String sanitizedText, Map<String, String> mapping, int replacementCount) {
    public TokenizationResult {
        mapping = Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
    }
}
