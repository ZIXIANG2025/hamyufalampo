package com.safedocs.verification;

import com.safedocs.core.PiiEntity;
import com.safedocs.detection.PiiDetector;
import com.safedocs.detection.Policies;
import com.safedocs.document.PdfTextExtractorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class PdfVerifier {

    @Autowired
    private PdfTextExtractorService extractor;

    @Autowired
    private PiiDetector detector;

    /**
     * 验证脱敏后的 PDF 是否还残留敏感信息
     * @return true 表示安全 (0个敏感词)，false 表示泄露
     */
    public boolean verifyIntegrity(byte[] redactedPdfBytes, List<Policies> checkedPolicies) throws IOException {
        // 1. 尝试从脱敏后的文件中再次提取文字
        String extractedText = extractor.extractText(redactedPdfBytes);

        // 2. 让 AI 引擎重新检查
        List<PiiEntity> remainingEntities = detector.analyzeText(extractedText, checkedPolicies);

        // 3. 如果结果为空，说明底层字符被彻底摧毁，验证通过
        return remainingEntities.isEmpty();
    }
}
