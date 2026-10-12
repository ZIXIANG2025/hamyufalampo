package com.safedocs.redaction;

import com.itextpdf.kernel.pdf.PdfDocument;

public class MetadataSanitizer {
    public static void clean(PdfDocument pdfDoc) {
        pdfDoc.getDocumentInfo().setTitle("");
        pdfDoc.getDocumentInfo().setAuthor("");
        pdfDoc.getDocumentInfo().setSubject("");
        pdfDoc.getDocumentInfo().setKeywords("");
        pdfDoc.getDocumentInfo().setCreator("SafeDocs Offline Sanitizer");
    }
}
