package com.interviewlab.workbench.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReportPdf {
    private static final float SIZE = 11;
    private static final float LEADING = 17;
    private static final int LINES_PER_PAGE = 43;
    private final String configuredFont;

    public ReportPdf(@Value("${WORKBENCH_PDF_FONT:}") String configuredFont) {
        this.configuredFont = configuredFont;
    }

    public byte[] render(String title, String text) {
        Path fontFile = fontFile();
        try (var document = new PDDocument(); var fontInput = Files.newInputStream(fontFile);
             var output = new ByteArrayOutputStream()) {
            var font = PDType0Font.load(document, fontInput);
            List<String> lines = wrap(title + "\n\n" + text, font);
            for (int start = 0; start < lines.size(); start += LINES_PER_PAGE) {
                var page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, SIZE);
                    stream.setLeading(LEADING);
                    stream.newLineAtOffset(45, page.getMediaBox().getHeight() - 52);
                    for (int row = start; row < Math.min(lines.size(), start + LINES_PER_PAGE); row++) {
                        stream.showText(lines.get(row));
                        stream.newLine();
                    }
                    stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成 PDF 失败，请检查字体配置。");
        }
    }

    private List<String> wrap(String text, PDType0Font font) throws IOException {
        var lines = new ArrayList<String>();
        for (String segment : text.replace("\t", "    ").split("\\R", -1)) {
            var current = new StringBuilder();
            float width = 0;
            for (int cp : segment.codePoints().toArray()) {
                String glyph = Character.isISOControl(cp) ? " " : Character.toString(cp);
                float advance;
                try {
                    advance = font.getStringWidth(glyph) * SIZE / 1000;
                } catch (IllegalArgumentException ex) {
                    glyph = "?";
                    advance = font.getStringWidth(glyph) * SIZE / 1000;
                }
                if (width + advance > 500) {
                    lines.add(current.toString());
                    current.setLength(0);
                    width = 0;
                }
                current.append(glyph);
                width += advance;
            }
            lines.add(current.toString());
        }
        return lines;
    }

    private Path fontFile() {
        if (!configuredFont.isBlank()) {
            Path path = Path.of(configuredFont);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        String windows = System.getenv("WINDIR");
        if (windows != null) {
            Path path = Path.of(windows, "Fonts", "simhei.ttf");
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "需要中文 TrueType 字体才能导出 PDF。请设置 WORKBENCH_PDF_FONT。");
    }
}
