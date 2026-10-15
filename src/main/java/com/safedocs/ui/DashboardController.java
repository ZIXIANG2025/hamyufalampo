package com.safedocs.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safedocs.core.TokenizedDocument;
import com.safedocs.detection.AiDetectionException;
import com.safedocs.detection.PiiDetector;
import com.safedocs.detection.Policies;
import com.safedocs.document.PdfTextExtractorService;
import com.safedocs.document.PdfTextSubstitutionService;
import com.safedocs.tokenization.LocalKeyStore;
import com.safedocs.tokenization.TokenizationEngine;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final LocalKeyStore localKeyStore;

    public DashboardController(PdfTextExtractorService textExtractor,
                               PiiDetector detector,
                               TokenizationEngine tokenizationEngine,
                               PdfTextSubstitutionService pdfTextSubstitutionService,
                               ObjectMapper objectMapper,
                               LocalKeyStore localKeyStore) {
        this.textExtractor = textExtractor;
        this.detector = detector;
        this.tokenizationEngine = tokenizationEngine;
        this.pdfTextSubstitutionService = pdfTextSubstitutionService;
        this.objectMapper = objectMapper;
        this.localKeyStore = localKeyStore;
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
                if (!selectedPolicies.contains(policy)) selectedPolicies.add(policy);
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
            String documentId = java.util.UUID.randomUUID().toString();
            List<LocalKeyStore.StoredDocument> savedDocuments = localKeyStore.loadAll();
            String tokenNamespace = uniqueTokenNamespace(savedDocuments, documentId);
            TokenizedDocument tokenized = tokenizationEngine.tokenize(text, detectedEntities, tokenNamespace);
            try {
                localKeyStore.save(documentId, filename, tokenized.mapping());
            } catch (IOException keyException) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Could not save the mapping in Desktop\\Tester1.txt. The existing file was not overwritten.");
            }
            var responseBody = new LinkedHashMap<String, Object>();
            responseBody.put("sanitizedText", tokenized.sanitizedText());
            responseBody.put("mapping", tokenized.mapping());
            responseBody.put("tokenCount", tokenized.tokenCount());
            responseBody.put("documentId", documentId);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseBody);
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

    @GetMapping(value = "/local-key", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> loadDesktopKey() {
        try {
            var documents = localKeyStore.loadAll();
            var summaries = documents.stream().map(document -> java.util.Map.of(
                    "id", document.id(),
                    "fileName", document.fileName(),
                    "tokenCount", document.mapping().size())).toList();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(java.util.Map.of("available", !documents.isEmpty(), "documents", summaries));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Desktop\\Tester1.txt could not be decrypted or read as a SafeDocs key file.");
        }
    }

    @PostMapping(value = "/consume-local-key", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> consumeLocalKey(@RequestParam("documentId") String documentId,
                                              @RequestParam("mapping") String mappingJson) {
        try {
            LinkedHashMap<String, String> mapping = objectMapper.readValue(mappingJson,
                    new TypeReference<LinkedHashMap<String, String>>() { });
            boolean deleted = localKeyStore.deleteDocumentIfMatches(documentId, mapping);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(java.util.Map.of("deleted", deleted));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The restored document was created, but its mapping could not be updated in Desktop\\Tester1.txt.");
        }
    }

    @PostMapping(value = "/match-local-key", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> matchLocalKey(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty PDF file.");
        }
        try {
            byte[] pdf = file.getBytes();
            List<LocalKeyStore.StoredDocument> documents = localKeyStore.loadAll();
            String embeddedId = pdfTextSubstitutionService.readTokenDocumentId(pdf).orElse(null);
            if (embeddedId != null) {
                for (LocalKeyStore.StoredDocument document : documents) {
                    if (document.id().equals(embeddedId)) {
                        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of(
                                "documentId", document.id(),
                                "fileName", document.fileName(),
                                "mapping", document.mapping(),
                                "matchedTokenCount", document.mapping().size()));
                    }
                }
            }
            java.util.Set<String> allTokens = documents.stream()
                    .flatMap(document -> document.mapping().keySet().stream())
                    .collect(java.util.stream.Collectors.toSet());
            var foundTokens = pdfTextSubstitutionService.findMatchingTargets(pdf, allTokens);
            List<KeyMatch> matches = new ArrayList<>();
            for (LocalKeyStore.StoredDocument document : documents) {
                long found = document.mapping().keySet().stream().filter(foundTokens::contains).count();
                if (found > 0) matches.add(new KeyMatch(document, (int) found));
            }
            matches.sort(Comparator.comparingInt(KeyMatch::matchedTokens).reversed()
                    .thenComparing(Comparator.comparingDouble(KeyMatch::coverage).reversed()));
            if (matches.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("No saved mapping tokens were found in this PDF. Choose a SafeDocs-tokenized PDF or an AI report containing its placeholders.");
            }
            KeyMatch match = matches.get(0);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of(
                    "documentId", match.document().id(),
                    "fileName", match.document().fileName(),
                    "mapping", match.document().mapping(),
                    "matchedTokenCount", match.matchedTokens()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not inspect this PDF or decrypt the saved local mappings.");
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
                                               @RequestParam("mapping") String mappingJson,
                                               @RequestParam(value = "documentId", required = false) String documentId) {
        return substitutePdf(file, mappingJson, documentId, true);
    }

    @PostMapping(value = "/restore-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> restorePdf(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "mapping", required = false) String mappingJson,
                                              @RequestParam(value = "documentId", required = false) String documentId) {
        return substitutePdf(file, mappingJson, documentId, false);
    }

    private ResponseEntity<byte[]> substitutePdf(MultipartFile file, String mappingJson, boolean encrypt) {
        return substitutePdf(file, mappingJson, null, encrypt);
    }

    private ResponseEntity<byte[]> substitutePdf(MultipartFile file, String mappingJson, String documentId, boolean encrypt) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty PDF file.");
        }
        try {
            LinkedHashMap<String, String> mapping;
            if (!encrypt && (mappingJson == null || mappingJson.isBlank())) {
                mapping = new LinkedHashMap<>(localKeyStore.load(documentId).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "No matching document mapping is available in Desktop\\Tester1.txt.")));
            } else {
                mapping = objectMapper.readValue(mappingJson, new TypeReference<LinkedHashMap<String, String>>() { });
            }
            if (mapping == null || mapping.size() > 10_000 || mapping.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The local key file is invalid.");
            }
            LinkedHashMap<String, String> substitutions = new LinkedHashMap<>();
            for (var entry : mapping.entrySet()) {
                if (encrypt) {
                    if (!entry.getKey().matches("\\[[A-Z][A-Z0-9_]*_\\d{3,}]")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The local key contains an invalid token.");
                    }
                    substitutions.put(entry.getValue(), entry.getKey());
                } else {
                    if (!entry.getKey().matches("\\[[A-Z][A-Z0-9_]*_\\d{3,}]")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The local key contains an invalid token.");
                    }
                    substitutions.put(entry.getKey(), entry.getValue());
                }
            }
            byte[] sourcePdf = file.getBytes();
            byte[] pdf = pdfTextSubstitutionService.substitute(sourcePdf, substitutions, encrypt, documentId);
            boolean storedKeyDeleted = false;
            if (!encrypt) {
                try {
                    boolean completeSafeDocsDocument = documentId != null
                            && pdfTextSubstitutionService.readTokenDocumentId(sourcePdf)
                            .filter(documentId::equals).isPresent()
                            && pdfTextSubstitutionService.findMatchingTargets(sourcePdf, mapping.keySet())
                            .containsAll(mapping.keySet());
                    if (completeSafeDocsDocument) {
                        storedKeyDeleted = localKeyStore.deleteDocumentIfMatches(documentId, mapping);
                    }
                } catch (IOException ignored) {
                    // Return the completed PDF if mapping cleanup cannot be confirmed safely.
                }
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(encrypt ? "SafeDocs-tokenized.pdf" : "SafeDocs-restored-report.pdf",
                            StandardCharsets.UTF_8).build());
            headers.setCacheControl(CacheControl.noStore());
            if (!encrypt) headers.set("X-SafeDocs-Key-Deleted", Boolean.toString(storedKeyDeleted));
            return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            String message = encrypt
                    ? "SafeDocs could not safely locate one or more sensitive text spans in this PDF. No tokenized PDF was created."
                    : "SafeDocs could not replace the matched tokens in this PDF. No restored PDF was created.";
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.TEXT_PLAIN)
                    .cacheControl(CacheControl.noStore())
                    .body(message.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record KeyMatch(LocalKeyStore.StoredDocument document, int matchedTokens) {
        private double coverage() {
            return document.mapping().isEmpty() ? 0 : (double) matchedTokens / document.mapping().size();
        }
    }

    private String uniqueTokenNamespace(List<LocalKeyStore.StoredDocument> documents, String documentId) {
        String compact = documentId.replace("-", "").toUpperCase(Locale.ROOT);
        String shortNamespace = compact.substring(0, 4);
        boolean inUse = documents.stream().flatMap(document -> document.mapping().keySet().stream())
                .anyMatch(token -> token.contains(shortNamespace + "_"));
        return inUse ? compact : shortNamespace;
    }

}
