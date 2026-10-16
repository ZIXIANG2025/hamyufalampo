package com.safedocs.tokenization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.WinCrypt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores local document mappings as readable JSON in Desktop\Tester1.txt. */
@Service
public class LocalKeyStore {
    private static final String FILE_NAME = "Tester1.txt";
    private static final String LEGACY_HEADER = "SAFEDOCS-DPAPI-V1\n";
    private static final String ENCRYPTED_HEADER = "SAFEDOCS-DPAPI-V2\n";
    private static final int FORMAT_VERSION = 1;

    private final ObjectMapper objectMapper;

    public LocalKeyStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized void save(String id, String fileName, Map<String, String> mapping) throws IOException {
        if (mapping == null || mapping.isEmpty()) return;
        LinkedHashMap<String, StoredDocument> documents = readDocuments();
        documents.put(id, new StoredDocument(id, safeFileName(fileName), new LinkedHashMap<>(mapping)));
        writeDocuments(documents);
    }

    public synchronized List<StoredDocument> loadAll() throws IOException {
        return List.copyOf(readDocuments().values());
    }

    public synchronized Optional<Map<String, String>> load(String documentId) throws IOException {
        if (documentId == null || documentId.isBlank()) return Optional.empty();
        StoredDocument document = readDocuments().get(documentId);
        return document == null ? Optional.empty() : Optional.of(Map.copyOf(document.mapping()));
    }

    /** Remove only the selected document mapping, and only if it still matches the restored mapping. */
    public synchronized boolean deleteDocumentIfMatches(String documentId, Map<String, String> mapping) throws IOException {
        LinkedHashMap<String, StoredDocument> documents = readDocuments();
        StoredDocument stored = documents.get(documentId);
        if (stored == null || !stored.mapping().equals(mapping)) return false;
        documents.remove(documentId);
        writeDocuments(documents);
        return true;
    }

    private LinkedHashMap<String, StoredDocument> readDocuments() throws IOException {
        Path file = desktopDirectory().resolve(FILE_NAME);
        LinkedHashMap<String, StoredDocument> documents = new LinkedHashMap<>();
        if (!Files.exists(file) || Files.size(file) == 0) return documents;

        byte[] bytes = Files.readAllBytes(file);
        String contents = new String(bytes, StandardCharsets.UTF_8);
        if (contents.isBlank()) {
            // Treat empty/whitespace-only files as an empty store and normalize them to JSON.
            writeDocuments(documents);
            return documents;
        }
        if (contents.startsWith(ENCRYPTED_HEADER)) {
            byte[] protectedData = decodeProtectedData(contents.substring(ENCRYPTED_HEADER.length()));
            byte[] plain = null;
            try {
                plain = Crypt32Util.cryptUnprotectData(protectedData, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
                LinkedHashMap<String, StoredDocument> loadedDocuments = parseDocuments(new String(plain, StandardCharsets.UTF_8));
                // Older versions protected the store with DPAPI. Rewrite it in the
                // current readable JSON format so mappings can be used directly.
                writeDocuments(loadedDocuments);
                return loadedDocuments;
            } catch (RuntimeException exception) {
                throw new IOException("Windows could not decrypt the protected mappings in Tester1.txt for this user.", exception);
            } finally {
                Arrays.fill(protectedData, (byte) 0);
                if (plain != null) Arrays.fill(plain, (byte) 0);
            }
        }
        if (contents.startsWith(LEGACY_HEADER)) {
            documents.put("legacy", new StoredDocument("legacy", "Legacy saved document", readLegacyMapping(contents)));
            writeDocuments(documents);
            return documents;
        }
        LinkedHashMap<String, StoredDocument> loadedDocuments = parseDocuments(contents);
        // Normalize older readable key files as soon as they are read.
        writeDocuments(loadedDocuments);
        return loadedDocuments;
    }

    private LinkedHashMap<String, StoredDocument> parseDocuments(String contents) throws IOException {
        LinkedHashMap<String, StoredDocument> documents = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(contents);
            if (root == null || root.path("version").asInt(-1) != FORMAT_VERSION || !root.path("documents").isArray()) {
                throw new IOException("Desktop\\Tester1.txt is not a SafeDocs document JSON file; it was left untouched.");
            }
            for (JsonNode item : root.path("documents")) {
                String id = item.path("id").asText(null);
                String name = item.path("fileName").asText("Unnamed document");
                JsonNode mappingNode = item.path("mapping");
                if (id == null || !mappingNode.isObject()) continue;
                LinkedHashMap<String, String> mapping = new LinkedHashMap<>();
                mappingNode.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) mapping.put(entry.getKey(), entry.getValue().asText());
                });
                documents.put(id, new StoredDocument(id, name, mapping));
            }
            return documents;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Desktop\\Tester1.txt contains invalid SafeDocs JSON.", exception);
        }
    }

    private Map<String, String> readLegacyMapping(String contents) throws IOException {
        byte[] protectedData = decodeProtectedData(contents.substring(LEGACY_HEADER.length()));
        byte[] plain = null;
        try {
            plain = Crypt32Util.cryptUnprotectData(protectedData, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
            Map<String, String> mapping = objectMapper.readValue(plain,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
            return mapping == null ? Map.of() : mapping;
        } catch (RuntimeException exception) {
            throw new IOException("Windows could not read the existing protected key in Tester1.txt.", exception);
        } finally {
            Arrays.fill(protectedData, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private byte[] decodeProtectedData(String encoded) throws IOException {
        try {
            return java.util.Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("The protected SafeDocs key file is damaged.", exception);
        }
    }

    private void writeDocuments(LinkedHashMap<String, StoredDocument> documents) throws IOException {
        Path desktop = desktopDirectory();
        Files.createDirectories(desktop);
        Path target = desktop.resolve(FILE_NAME);
        if (Files.exists(target) && Files.size(target) > 0) {
            String existing = Files.readString(target, StandardCharsets.UTF_8);
            if (!existing.isBlank()
                    && !existing.startsWith(LEGACY_HEADER)
                    && !existing.startsWith(ENCRYPTED_HEADER)) {
                JsonNode root;
                try {
                    root = objectMapper.readTree(existing);
                } catch (IOException exception) {
                    throw new IOException("Desktop\\Tester1.txt is not a SafeDocs JSON file; it was left untouched.", exception);
                }
                if (root == null || root.path("version").asInt(-1) != FORMAT_VERSION || !root.path("documents").isArray()) {
                    throw new IOException("Desktop\\Tester1.txt is not a SafeDocs JSON file; it was left untouched.");
                }
            }
        }

        List<String> documentJson = new ArrayList<>();
        for (StoredDocument document : documents.values()) {
            documentJson.add(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document));
        }
        String json = "{\n"
                + "  \"version\": " + FORMAT_VERSION + ",\n"
                + "  \"documents\": [\n"
                + "    " + String.join(",\n\n    ", documentJson).replace("\n", "\n    ") + "\n"
                + "  ]\n"
                + "}\n";
        Path temporary = Files.createTempFile(desktop, ".safedocs-key-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "Unnamed document";
        return Path.of(fileName).getFileName().toString();
    }

    private Path desktopDirectory() throws IOException {
        try {
            return Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Desktop));
        } catch (RuntimeException exception) {
            throw new IOException("Windows could not resolve the current user's Desktop folder.", exception);
        }
    }

    public record StoredDocument(String id, String fileName, Map<String, String> mapping) { }
}
