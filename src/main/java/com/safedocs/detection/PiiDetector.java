package com.safedocs.detection;

import com.safedocs.core.PiiEntity;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class PiiDetector {
    private final AiPiiDetector aiPiiDetector;

    public PiiDetector(AiPiiDetector aiPiiDetector) {
        this.aiPiiDetector = aiPiiDetector;
    }

    public List<PiiEntity> analyzeText(String text, List<Policies> activePolicies) {
        if (activePolicies == null || activePolicies.isEmpty()) {
            throw new IllegalArgumentException("Select at least one detection category.");
        }
        return aiPiiDetector.detect(text, activePolicies);
    }
}
