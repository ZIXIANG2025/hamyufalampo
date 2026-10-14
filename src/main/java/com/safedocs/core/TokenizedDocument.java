package com.safedocs.core;

import java.util.Map;

public record TokenizedDocument(String sanitizedText, Map<String, String> mapping, int tokenCount) {
}
