package com.safedocs.detection;

import com.safedocs.core.PiiEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PiiDetector {

    private final AiPiiDetector aiPiiDetector;

    public PiiDetector(AiPiiDetector aiPiiDetector) {
        this.aiPiiDetector = aiPiiDetector;
    }

    public List<PiiEntity> analyzeText(String text, List<Policies> activePolicies) {
        if (activePolicies == null || activePolicies.isEmpty()) {
            return List.of();
        }
        Set<Policies> selected = Set.copyOf(activePolicies);
        return aiPiiDetector.detect(text).stream()
                .filter(entity -> selected.stream().anyMatch(policy -> policy.includes(entity.getEntityType())))
                .collect(Collectors.toList());
    }
}
