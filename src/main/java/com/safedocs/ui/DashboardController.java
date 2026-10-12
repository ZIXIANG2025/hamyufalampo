package com.safedocs.ui;

import com.safedocs.detection.Policies;
import com.safedocs.redaction.PdfRedactor;
import com.safedocs.verification.PdfVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/safedocs")
public class DashboardController {

    @Autowired
    private PdfRedactor redactor;

    @Autowired
    private PdfVerifier verifier;

    @PostMapping("/process")
    public ResponseEntity<?> processDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "policies", required = false) List<String> policyStrings) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload a non-empty PDF file.");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported.");
        }
        if (policyStrings == null || policyStrings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one PII policy.");
        }

        try {
            // Parse and validate the policies submitted by the client.
            List<Policies> policies = new ArrayList<>();
            for (String value : policyStrings) {
                if (value == null || value.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Policy names cannot be blank.");
                }
                try {
                    Policies policy = Policies.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
                    if (!policies.contains(policy)) {
                        policies.add(policy);
                    }
                } catch (IllegalArgumentException exception) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported PII policy: " + value);
                }
            }

            // Redact the PDF, then verify that selected PII patterns are absent.
            byte[] rawBytes = file.getBytes();
            byte[] redactedBytes = redactor.applyRedaction(rawBytes, policies);
            boolean isSecure = verifier.verifyIntegrity(redactedBytes, policies);
            if (!isSecure) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Security Check Failed: PII leakage detected in output file.");
            }

            String safeFilename = originalFilename.replaceAll("[\\\\/\\r\\n\\\"]", "_");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("Secure_" + safeFilename, StandardCharsets.UTF_8)
                    .build());

            return new ResponseEntity<>(redactedBytes, headers, HttpStatus.OK);

        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Unable to process the uploaded PDF. Ensure the file is valid and not password-protected.");
        }
    }
}
