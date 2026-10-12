package com.safedocs.detection;

public class MalaysiaRules {
    public static final String MYKAD_REGEX = "\\b\\d{6}-\\d{2}-\\d{4}\\b";
    public static final String MYPHONE_REGEX = "\\b(?:\\+?6?01)[0-46-9]-*[0-9]{7,8}\\b";
    public static final String EMAIL_REGEX = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
}
