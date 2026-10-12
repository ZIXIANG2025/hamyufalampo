package com.safedocs.detection;

public class AiDetectionException extends RuntimeException {
    public AiDetectionException(String message) {
        super(message);
    }

    public AiDetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
