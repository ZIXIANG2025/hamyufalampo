package com.safedocs.verification;

import com.safedocs.core.PiiEntity;
import com.safedocs.detection.PiiDetector;
import com.safedocs.detection.Policies;
import com.safedocs.detection.AiDetectionException;
import com.safedocs.document.PdfTextExtractorService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Service
public class PdfVerifier {

    private final PdfTextExtractorService extractor;
    private final PiiDetector detector;

    public PdfVerifier(PdfTextExtractorService extractor, PiiDetector detector) {
        this.extractor = extractor;
        this.detector = detector;
    }

    /**
     * Return true only when known matches are gone and an independent AI scan finds no more.
     */
    public boolean verifyIntegrity(byte[] redactedPdfBytes, List<Policies> checkedPolicies,
                                   List<PiiEntity> originalEntities) throws IOException {
        String extractedText = extractor.extractText(redactedPdfBytes);
        String normalizedOutput = normalize(extractedText);
        for (PiiEntity entity : originalEntities) {
            String match = entity.getMatchedText();
            if (match != null && !match.isBlank() && normalizedOutput.contains(normalize(match))) {
                return false;
            }
        }

        List<PiiEntity> remainingEntities;
        try {
            remainingEntities = detector.analyzeText(extractedText, checkedPolicies);
        } catch (AiDetectionException e) {
            throw new IOException("Independent AI verification did not complete; the output must not be released.", e);
        }

        return remainingEntities.isEmpty();
    }

    private String normalize(String text) {
        return text.replaceAll("\s+", "").toLowerCase(Locale.ROOT);
    }
}
