package com.safedocs.redaction;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.pdfcleanup.PdfCleaner;
import com.itextpdf.pdfcleanup.autosweep.CompositeCleanupStrategy;
import com.itextpdf.pdfcleanup.autosweep.RegexBasedCleanupStrategy;
import com.safedocs.detection.MalaysiaRules;
import com.safedocs.detection.Policies;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class PdfRedactor {

    public byte[] applyRedaction(byte[] fileBytes, List<Policies> policies) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        PdfDocument pdfDoc = new PdfDocument(new PdfReader(bais), new PdfWriter(baos));

        // 1. 调用 Sanitizer 清理元数据
        MetadataSanitizer.clean(pdfDoc);

        // 2. 组装清理策略 (pdfSweep 底层破坏性删除)
        CompositeCleanupStrategy strategy = new CompositeCleanupStrategy();

        if (policies.contains(Policies.MYKAD)) {
            strategy.add(new RegexBasedCleanupStrategy(MalaysiaRules.MYKAD_REGEX).setRedactionColor(ColorConstants.BLACK));
        }
        if (policies.contains(Policies.PHONE)) {
            strategy.add(new RegexBasedCleanupStrategy(MalaysiaRules.MYPHONE_REGEX).setRedactionColor(ColorConstants.BLACK));
        }
        if (policies.contains(Policies.EMAIL)) {
            strategy.add(new RegexBasedCleanupStrategy(MalaysiaRules.EMAIL_REGEX).setRedactionColor(ColorConstants.BLACK));
        }

        // 3. 执行脱敏
        PdfCleaner.autoSweepCleanUp(pdfDoc, strategy);
        pdfDoc.close();

        return baos.toByteArray();
    }
}
