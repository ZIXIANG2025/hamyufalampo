package com.safedocs.ui;

import com.safedocs.detection.AiDetectionException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = DashboardController.class)
public class ApiExceptionHandler {
    public record ApiError(String message) { }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> requestError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(new ApiError(exception.getReason()));
    }

    @ExceptionHandler(AiDetectionException.class)
    ResponseEntity<ApiError> detectionError(AiDetectionException exception) {
        return error(HttpStatus.BAD_GATEWAY, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> sizeError() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "Choose a PDF no larger than 20 MB.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> missingFile() {
        return error(HttpStatus.BAD_REQUEST, "Choose a PDF file.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> processingError() {
        // Do not expose document content, local key values, or model response bodies.
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Tokenization could not complete. No output was returned.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(message));
    }
}
