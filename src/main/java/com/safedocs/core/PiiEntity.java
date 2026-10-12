package com.safedocs.core;

public class PiiEntity {
    private String entityType;
    private String matchedText;

    public PiiEntity(String entityType, String matchedText) {
        this.entityType = entityType;
        this.matchedText = matchedText;
    }

    public String getEntityType() { return entityType; }
    public String getMatchedText() { return matchedText; }
}
