package com.safedocs.document;

import com.itextpdf.kernel.geom.Rectangle;

public record PdfTextSpan(int pageNumber, String text, Rectangle rectangle) {
}
