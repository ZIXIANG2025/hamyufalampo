package com.safedocs.redaction;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.safedocs.core.PiiEntity;
import com.safedocs.detection.Policies;
import com.safedocs.document.PdfTextExtractorService;
import com.safedocs.document.PdfTextLocator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PdfRedactionIntegrationTest {
    @Test
    void removesPageTextAndDocumentMetadata() throws Exception {
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(source));
             Document document = new Document(pdf)) {
            pdf.getDocumentInfo().setTitle("Alice private title");
            pdf.getDocumentInfo().setMoreInfo("CustomSecret", "Alice custom metadata");
            PdfStream xmp = new PdfStream("<xmp>Alice private metadata</xmp>".getBytes(StandardCharsets.UTF_8));
            xmp.put(PdfName.Type, PdfName.Metadata);
            pdf.getCatalog().put(PdfName.Metadata, xmp);
            document.add(new Paragraph("Alice Smith signed the form."));
        }

        byte[] redacted = new PdfRedactor(new PdfTextLocator()).applyRedaction(
                source.toByteArray(), List.of(Policies.AI_PII),
                List.of(new PiiEntity("PERSON", "Alice Smith")));

        assertFalse(new PdfTextExtractorService().extractText(redacted).contains("Alice Smith"));
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(redacted)))) {
            assertNull(pdf.getDocumentInfo().getTitle());
            assertNull(pdf.getDocumentInfo().getMoreInfo("CustomSecret"));
            byte[] xmp = pdf.getXmpMetadata();
            if (xmp != null) {
                assertFalse(new String(xmp, StandardCharsets.UTF_8).contains("Alice"));
            }
        }
    }
}
