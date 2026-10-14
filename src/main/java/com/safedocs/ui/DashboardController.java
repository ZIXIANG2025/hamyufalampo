package com.safedocs.ui;

import com.safedocs.core.TokenizedDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safedocs.detection.AiDetectionException;
import com.safedocs.detection.PiiDetector;
import com.safedocs.detection.Policies;
import com.safedocs.document.PdfTextExtractorService;
import com.safedocs.document.PdfTextSubstitutionService;
import com.safedocs.tokenization.TokenizationEngine;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/safedocs")
public class DashboardController {
    private final PdfTextExtractorService textExtractor;
    private final PiiDetector detector;
    private final TokenizationEngine tokenizationEngine;
    private final PdfTextSubstitutionService pdfTextSubstitutionService;
    private final ObjectMapper objectMapper;

    public DashboardController(PdfTextExtractorService textExtractor,
                               PiiDetector detector,
                               TokenizationEngine tokenizationEngine,
                               PdfTextSubstitutionService pdfTextSubstitutionService,
                               ObjectMapper objectMapper) {
        this.textExtractor = textExtractor;
        this.detector = detector;
        this.tokenizationEngine = tokenizationEngine;
        this.pdfTextSubstitutionService = pdfTextSubstitutionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/tokenize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tokenizeDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "policies", required = false) List<String> policyStrings) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload a non-empty PDF file.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported.");
        }
        if (policyStrings == null || policyStrings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one PII category.");
        }

        List<Policies> selectedPolicies = new ArrayList<>();
        try {
            for (String value : policyStrings) {
                if (value == null || value.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Policy names cannot be blank.");
                }
                Policies policy = Policies.valueOf(value.trim().toUpperCase(Locale.ROOT));
                if (!selectedPolicies.contains(policy)) {
                    selectedPolicies.add(policy);
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported AI detection category.");
        }

        try {
            String text = textExtractor.extractText(file.getBytes());
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No extractable text was found. Scanned/image PDFs require a local OCR engine.");
            }
            var detectedEntities = detector.analyzeText(text, selectedPolicies);
            TokenizedDocument tokenized = tokenizationEngine.tokenize(text, detectedEntities);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(tokenized);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (AiDetectionException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .cacheControl(CacheControl.noStore())
                    .body(exception.getMessage());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unable to read the uploaded PDF. Ensure it is valid and not password-protected.");
        }
    }

    @PostMapping(value = "/extract-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> extractPdfText(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty PDF file.");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body(textExtractor.extractText(file.getBytes()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unable to read the selected PDF. Ensure it is valid and not password-protected.");
        }
    }

    @PostMapping(value = "/tokenize-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> tokenizePdf(@RequestParam("file") MultipartFile file,
                                               @RequestParam("mapping") String mappingJson) {
        return substitutePdf(file, mappingJson, true);
    }

    @PostMapping(value = "/restore-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> restorePdf(@RequestParam("file") MultipartFile file,
                                              @RequestParam("mapping") String mappingJson) {
        return substitutePdf(file, mappingJson, false);
    }

    private ResponseEntity<byte[]> substitutePdf(MultipartFile file, String mappingJson, boolean encrypt) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty PDF file.");
        }
        try {
            var mapping = objectMapper.readValue(mappingJson, new TypeReference<LinkedHashMap<String, String>>() { });
            if (mapping == null || mapping.size() > 10_000 || mapping.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The local key file is invalid.");
            }
            LinkedHashMap<String, String> substitutions = new LinkedHashMap<>();
            for (var entry : mapping.entrySet()) {
                if (encrypt) {
                    if (!entry.getKey().matches("\\[[A-Z][A-Z0-9]*_\\d{3,}]")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The local key contains an invalid token.");
                    }
                    substitutions.put(entry.getValue(), entry.getKey());
                } else {
                    if (!entry.getKey().matches("\\[[A-Z][A-Z0-9]*_\\d{3,}]")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The local key contains an invalid token.");
                    }
                    substitutions.put(entry.getKey(), entry.getValue());
                }
            }
            byte[] pdf = pdfTextSubstitutionService.substitute(file.getBytes(), substitutions);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(encrypt ? "SafeDocs-tokenized.pdf" : "SafeDocs-restored-report.pdf",
                            StandardCharsets.UTF_8).build());
            headers.setCacheControl(CacheControl.noStore());
            return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not substitute text in this PDF while preserving its layout: " + exception.getMessage());
        }
    }
}
