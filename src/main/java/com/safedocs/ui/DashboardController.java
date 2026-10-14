package com.safedocs.ui;

import com.safedocs.detection.Policies;
import com.safedocs.detection.PiiDetector;
import com.safedocs.detection.AiDetectionException;
import com.safedocs.redaction.PdfRedactor;
import com.safedocs.verification.PdfVerifier;
import com.safedocs.document.PdfTextExtractorService;
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

    private final PdfRedactor redactor;
    private final PdfVerifier verifier;
    private final PdfTextExtractorService textExtractor;
    private final PiiDetector detector;

    public DashboardController(PdfRedactor redactor, PdfVerifier verifier,
                               PdfTextExtractorService textExtractor, PiiDetector detector) {
        this.redactor = redactor;
        this.verifier = verifier;
        this.textExtractor = textExtractor;
        this.detector = detector;
    }

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
                if (!value.trim().matches("PERSONAL|CONTACT|FINANCIAL|SENSITIVE")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported AI detection category.");
                }
                if (!policies.contains(Policies.AI_PII)) {
                    policies.add(Policies.AI_PII);
                }
            }
            if (policies.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one AI detection category.");
            }

            // Reject PDFs without extractable text. This app doesn't yet run local OCR on scanned/image PDFs.
            byte[] rawBytes = file.getBytes();
            String originalText = textExtractor.extractText(rawBytes);
            if (originalText.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No extractable text was found. Scanned/image PDFs require a local OCR engine before processing.");
            }
            // Detect all PII semantically, then redact and independently verify before returning a PDF.
            var detectedEntities = detector.analyzeText(originalText, policies);
            byte[] redactedBytes = redactor.applyRedaction(rawBytes, policies, detectedEntities);
            boolean isSecure = verifier.verifyIntegrity(redactedBytes, policies, detectedEntities);
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
        } catch (AiDetectionException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(exception.getMessage());
        } catch (java.io.IOException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Secure processing or independent verification could not complete. No output file was returned.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Unable to process the uploaded PDF. Ensure the file is valid and not password-protected.");
        }
    }
}
