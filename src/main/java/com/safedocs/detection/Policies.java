package com.safedocs.detection;

import java.util.List;

public enum Policies {
    PERSONAL(List.of("NAME", "IC", "MATRIC", "GENDER", "PASSPORT", "IDENTIFIER", "SIGNATURE")),
    CONTACT(List.of("PHONE", "EMAIL", "ADDRESS", "LOCATION")),
    FINANCIAL(List.of("BANK_ACCOUNT", "CARD", "TAX", "FINANCIAL")),
    SENSITIVE(List.of("DATE_OF_BIRTH", "MEDICAL", "OTHER"));

    private final List<String> entityTypes;

    Policies(List<String> entityTypes) {
        this.entityTypes = entityTypes;
    }

    public List<String> entityTypes() {
        return entityTypes;
    }
}
