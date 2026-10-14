package com.safedocs.redaction;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfDocumentInfo;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.xmp.XMPException;
import com.itextpdf.kernel.xmp.XMPMetaFactory;

import java.util.List;

public final class MetadataSanitizer {
    private MetadataSanitizer() {
    }

    public static void clean(PdfDocument pdfDoc, List<String> sourceInfoKeys) throws XMPException {
        // iText caches both source XMP and the Info dictionary when opening a document.
        pdfDoc.setXmpMetadata(XMPMetaFactory.create());
        PdfDocumentInfo documentInfo = pdfDoc.getDocumentInfo();
        for (String key : sourceInfoKeys) {
            documentInfo.setMoreInfo(key, null);
        }
        for (String key : new String[]{"Title", "Author", "Subject", "Keywords", "Producer", "Trapped"}) {
            documentInfo.setMoreInfo(key, null);
        }
        documentInfo.removeCreationDate();
        documentInfo.setMoreInfo("ModDate", null);
        documentInfo.setCreator("SafeDocs Offline Sanitizer");

        // XMP can duplicate document properties independently of the Info dictionary.
        pdfDoc.getCatalog().remove(PdfName.Metadata);
        for (int page = 1; page <= pdfDoc.getNumberOfPages(); page++) {
            pdfDoc.getPage(page).getPdfObject().remove(PdfName.Metadata);
        }
    }
}
