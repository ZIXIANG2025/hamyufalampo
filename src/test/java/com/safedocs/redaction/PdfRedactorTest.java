package com.safedocs.redaction;

import com.itextpdf.kernel.geom.Rectangle;
import com.safedocs.document.PdfTextSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRedactorTest {
    private final PdfRedactor redactor = new PdfRedactor(null);

    @Test
    void locatesTextAfterSupplementaryUnicodeCharacters() {
        PdfTextSpan emoji = span(1, "😀");
        PdfTextSpan secret = span(1, "Secret");

        assertEquals(List.of(secret), redactor.locateExactMatches(List.of(emoji, secret), "Secret"));
    }

    @Test
    void doesNotMatchAcrossPdfPages() {
        assertTrue(redactor.locateExactMatches(List.of(span(1, "Sec"), span(2, "ret")),
                "Secret").isEmpty());
    }

    private PdfTextSpan span(int page, String text) {
        return new PdfTextSpan(page, text, new Rectangle(0, 0, 10, 10));
    }
}
