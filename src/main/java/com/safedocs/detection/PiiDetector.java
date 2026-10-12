package com.safedocs.detection;

import com.safedocs.core.PiiEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PiiDetector {

    public List<PiiEntity> analyzeText(String text, List<Policies> activePolicies) {
        List<PiiEntity> detectedEntities = new ArrayList<>();

        if (activePolicies.contains(Policies.MYKAD)) {
            findMatches(text, MalaysiaRules.MYKAD_REGEX, "MYKAD", detectedEntities);
        }
        if (activePolicies.contains(Policies.PHONE)) {
            findMatches(text, MalaysiaRules.MYPHONE_REGEX, "PHONE", detectedEntities);
        }
        if (activePolicies.contains(Policies.EMAIL)) {
            findMatches(text, MalaysiaRules.EMAIL_REGEX, "EMAIL", detectedEntities);
        }
        return detectedEntities;
    }

    private void findMatches(String text, String regex, String type, List<PiiEntity> results) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            results.add(new PiiEntity(type, matcher.group()));
        }
    }
}
