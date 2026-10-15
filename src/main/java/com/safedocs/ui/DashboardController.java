package com.safedocs.ui;

import com.safedocs.detection.Policies;
import com.safedocs.detection.PiiDetector;
import com.safedocs.document.PdfTextExtractorService;
import com.safedocs.tokenization.TokenizationResult;
import com.safedocs.tokenization.TokenizationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/safedocs")
public class DashboardController {
    private final PdfTextExtractorService textExtractor;
    private final PiiDetector detector;
    private final TokenizationService tokenizer;

    public DashboardController(PdfTextExtractorService textExtractor, PiiDetector detector,
                               TokenizationService tokenizer) {
        this.textExtractor = textExtractor;
        this.detector = detector;
        this.tokenizer = tokenizer;
    }

    @PostMapping(value = "/tokenize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenizationResult> tokenizeDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "policies", required = false) List<String> policyStrings) {
        if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024) {
            throw badRequest("Choose a non-empty PDF no larger than 20 MB.");
        }
        if (file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw badRequest("Only PDF files are supported.");
        }
        if (policyStrings == null || policyStrings.isEmpty()) {
            throw badRequest("Select at least one detection category.");
        }
        List<Policies> policies = new ArrayList<>();
        for (String value : policyStrings) {
            try {
                Policies policy = Policies.valueOf(value.trim());
                if (!policies.contains(policy)) policies.add(policy);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw badRequest("Unsupported detection category.");
            }
        }
        String text;
        try {
            text = textExtractor.extractText(file.getBytes());
        } catch (Exception exception) {
            throw badRequest("Unable to read this PDF. Choose a valid PDF without password protection.");
        }
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No extractable text was found. Scanned PDFs need local OCR first.");
        }
        var entities = detector.analyzeText(text, policies);
        var result = tokenizer.tokenize(text, entities);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
