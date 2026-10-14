package com.safedocs.document;

import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.Rectangle;

public record PdfTextSpan(int pageNumber, String text, Rectangle rectangle,
                          float baselineX, float baselineY, float baselineA, float baselineB,
                          PdfFont font, float fontSize, Color color) {
}
