package com.safedocs.verification;

import com.safedocs.core.PiiEntity;
import com.safedocs.detection.PiiDetector;
import com.safedocs.detection.Policies;
import com.safedocs.document.PdfTextExtractorService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PdfVerifierTest {
    @Test
    void rejectsKnownTextEvenIfTheSecondAiScanWouldMissIt() throws IOException {
        PdfTextExtractorService extractor = mock(PdfTextExtractorService.class);
        PiiDetector detector = mock(PiiDetector.class);
        when(extractor.extractText(any())).thenReturn("The account belongs to Alice Smith.");

        PdfVerifier verifier = new PdfVerifier(extractor, detector);
        assertFalse(verifier.verifyIntegrity(new byte[0], List.of(Policies.AI_PII),
                List.of(new PiiEntity("PERSON", "Alice  Smith"))));
        verifyNoInteractions(detector);
    }
}
