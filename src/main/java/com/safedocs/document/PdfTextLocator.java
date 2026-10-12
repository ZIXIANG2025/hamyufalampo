package com.safedocs.document;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class PdfTextLocator {

    public List<PdfTextSpan> locateAll(PdfDocument pdf) throws IOException {
        List<PdfTextSpan> spans = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
            int detectedPageNumber = pageNumber;
            IEventListener strategy = new IEventListener() {
                @Override
                public void eventOccurred(IEventData data, EventType type) {
                    if (type != EventType.RENDER_TEXT || !(data instanceof TextRenderInfo textInfo)) {
                        return;
                    }
                    for (TextRenderInfo character : textInfo.getCharacterRenderInfos()) {
                        String value = character.getText();
                        if (!value.isEmpty()) {
                            spans.add(new PdfTextSpan(detectedPageNumber, value,
                                    character.getAscentLine().getBoundingRectangle()));
                        }
                    }
                }

                @Override
                public Set<EventType> getSupportedEvents() {
                    return Set.of(EventType.RENDER_TEXT);
                }
            };
            new PdfCanvasProcessor(strategy).processPageContent(pdf.getPage(pageNumber));
        }
        return spans;
    }
}
