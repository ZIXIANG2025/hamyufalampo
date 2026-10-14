package com.safedocs.detection;

public enum Policies {
    PERSONAL,
    CONTACT,
    FINANCIAL,
    SENSITIVE;

    public boolean includes(String entityType) {
        if (entityType == null) {
            return false;
        }
        return switch (this) {
            case PERSONAL -> switch (entityType) {
                case "PERSON", "PASSPORT", "MYKAD", "IC", "MATRIC", "STUDENT_ID", "IDENTIFIER", "SIGNATURE" -> true;
                default -> false;
            };
            case CONTACT -> switch (entityType) {
                case "ADDRESS", "PHONE", "EMAIL" -> true;
                default -> false;
            };
            case FINANCIAL -> switch (entityType) {
                case "BANK_ACCOUNT", "CREDIT_CARD", "TAX_ID", "FINANCIAL" -> true;
                default -> false;
            };
            case SENSITIVE -> switch (entityType) {
                case "DATE_OF_BIRTH", "GENDER", "MEDICAL", "OTHER" -> true;
                default -> false;
            };
        };
    }
}
