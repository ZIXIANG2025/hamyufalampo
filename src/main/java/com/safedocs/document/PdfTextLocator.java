package com.safedocs.document;

import com.itextpdf.kernel.geom.LineSegment;
import com.itextpdf.kernel.geom.Vector;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class PdfTextLocator {
    public List<PdfTextSpan> locateAll(PdfDocument pdf) {
        List<PdfTextSpan> spans = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
            int page = pageNumber;
            IEventListener listener = new IEventListener() {
                @Override
                public void eventOccurred(IEventData data, EventType type) {
                    if (type != EventType.RENDER_TEXT || !(data instanceof TextRenderInfo info)) return;
                    for (TextRenderInfo character : info.getCharacterRenderInfos()) {
                        String text = character.getText();
                        if (text == null || text.isEmpty()) continue;
                        LineSegment baseline = character.getBaseline();
                        Vector start = baseline.getStartPoint();
                        Vector end = baseline.getEndPoint();
                        float dx = end.get(0) - start.get(0);
                        float dy = end.get(1) - start.get(1);
                        float length = (float) Math.sqrt(dx * dx + dy * dy);
                        if (length < 0.001f) length = 1f;
                        spans.add(new PdfTextSpan(page, text,
                                character.getAscentLine().getBoundingRectangle(),
                                start.get(0), start.get(1), dx / length, dy / length,
                                character.getFont(), character.getFontSize(), character.getFillColor()));
                    }
                }

                @Override
                public Set<EventType> getSupportedEvents() {
                    return Set.of(EventType.RENDER_TEXT);
                }
            };
            new PdfCanvasProcessor(listener).processPageContent(pdf.getPage(pageNumber));
        }
        return spans;
    }
}
