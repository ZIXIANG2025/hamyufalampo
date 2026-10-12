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
        if (activePolicies == null || !activePolicies.contains(Policies.AI_PII)) {
            return List.of();
        }
        return aiPiiDetector.detect(text);
    }
}
