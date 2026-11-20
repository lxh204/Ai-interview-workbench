package com.interviewlab.workbench.resume;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public final class ResumeFiles {
    static final int MAX_TEXT = 30_000;
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;

    private ResumeFiles() {}

    static String read(String name, byte[] bytes) {
        String filename = name == null ? "" : name.toLowerCase(Locale.ROOT);
        try {
            if (filename.endsWith(".pdf")) {
                try (var document = Loader.loadPDF(bytes)) {
                    if (document.getNumberOfPages() > 25) {
                        throw bad("请上传 25 页以内的 PDF。");
                    }
                    return clean(new PDFTextStripper().getText(document));
                }
            }
            if (filename.endsWith(".docx")) {
                return clean(readDocx(bytes));
            }
            if (filename.endsWith(".txt") || filename.endsWith(".md")) {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", "");
                return clean(text);
            }
            throw bad("支持 PDF、DOCX、UTF-8 TXT 和 Markdown 文件。");
        } catch (CharacterCodingException ex) {
            throw bad("文本文件需要使用 UTF-8 编码。");
        } catch (IOException | SAXException | ParserConfigurationException ex) {
            throw bad("无法提取文件文本，请检查文件格式。");
        }
    }

    static String clean(String raw) {
        String text = raw.strip();
        if (text.isBlank() || text.length() > MAX_TEXT || text.indexOf('\0') >= 0) {
            throw bad("请提供 1 至 30000 字符的可读简历文本。扫描版 PDF 可先提取文字。");
        }
        return text;
    }

    public static String readDocx(byte[] bytes) throws IOException, SAXException, ParserConfigurationException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int remaining = MAX_DOCUMENT_BYTES;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                byte[] content = zip.readNBytes(remaining + 1);
                remaining -= content.length;
                if (remaining < 0) {
                    throw bad("DOCX 解包后文本资料超过限制。");
                }
                if (!"word/document.xml".equals(entry.getName())) {
                    continue;
                }
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
                var paragraphs = xml.getElementsByTagNameNS(WORD_NS, "p");
                var text = new StringBuilder();
                for (int index = 0; index < paragraphs.getLength(); index++) {
                    var runs = ((Element) paragraphs.item(index)).getElementsByTagNameNS(WORD_NS, "t");
                    for (int run = 0; run < runs.getLength(); run++) {
                        text.append(runs.item(run).getTextContent());
                    }
                    text.append('\n');
                }
                return text.toString();
            }
        }
        throw bad("DOCX 未包含可读正文。");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
