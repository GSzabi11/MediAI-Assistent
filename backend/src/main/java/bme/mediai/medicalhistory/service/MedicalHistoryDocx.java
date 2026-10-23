package bme.mediai.medicalhistory.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import bme.mediai.patient.persistence.Patient;

// DOCX-export a minta szerint: a Word "altChunk" — a dokumentum egyetlen beágyazott HTML-részt
// (MHT) tartalmaz, amit a Word nyitáskor alakít át. Így a szerkesztő HTML-je változtatás nélkül
// kerül a fájlba, külön könyvtár nélkül (java.util.zip). Korlát: a Word jól nyitja, a LibreOffice /
// Google Docs az altChunkot gyengén vagy egyáltalán nem kezeli.
public final class MedicalHistoryDocx {

    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final String STYLE = """
            body,p,li{font-family:'Times New Roman',serif;font-size:12pt;color:#000;line-height:1.35;}
            h1{font-size:15pt;text-align:center;margin:0 0 12pt;}
            h2{font-size:13pt;border-bottom:1px solid #000;margin:14pt 0 6pt;padding-bottom:2pt;}
            p{margin:4pt 0;} ul,ol{margin:4pt 0;padding-left:22pt;} li{margin:2pt 0;}
            p.tl-desc{margin-left:16px;}
            hr{border:none;border-top:1px solid #000;margin:8pt 0;}
            .note{font-size:9pt;color:#444;}""";

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/afchunk.mht" ContentType="message/rfc822"/>
            </Types>""";

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>""";

    private static final String DOCUMENT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="htmlChunk" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/aFChunk" Target="/word/afchunk.mht"/>
            </Relationships>""";

    // A4, 2 cm-es margó
    private static final String DOCUMENT = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <w:body>
                <w:altChunk r:id="htmlChunk"/>
                <w:sectPr>
                  <w:pgSz w:w="11906" w:h="16838" w:orient="portrait"/>
                  <w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="709" w:footer="709" w:gutter="0"/>
                </w:sectPr>
              </w:body>
            </w:document>""";

    private MedicalHistoryDocx() {
    }

    // body: a (szűrt) szerkesztő-HTML; madeOn: a "Készült" dátum
    public static byte[] build(Patient patient, String body, LocalDate madeOn, boolean edited) {
        return fromHtml("<h1>ANAMNÉZIS / KÓRTÖRTÉNETI ÖSSZEFOGLALÓ</h1>"
                + "<p><b>Beteg:</b> " + MedicalHistoryHtml.escape(patient.getName()) + "</p>"
                + "<p><b>TAJ:</b> " + taj(patient.getTaj()) + " &nbsp;&nbsp;<b>Születési dátum:</b> "
                + (patient.getBirthDate() == null ? "–" : patient.getBirthDate()) + "</p>"
                + "<p><i>Készült: " + madeOn + "</i></p><hr>"
                + body
                + "<hr><p class=\"note\"><i>"
                + (edited ? "AI által előkészített, orvos által szerkesztett összefoglaló."
                        : "AI által előkészített összefoglaló — orvosi ellenőrzés nélkül.")
                + " Döntéstámogatásra; a végső döntés az orvosé.</i></p>");
    }

    // Tetszőleges (már escape-elt / szűrt) HTML-törzs DOCX-ként, a közös stílussal — az epikrízis is ezt használja
    public static byte[] fromHtml(String bodyHtml) {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>" + STYLE + "</style></head><body>"
                + bodyHtml + "</body></html>";
        String mht = "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/related; type=\"text/html\"; boundary=\"----=mhtDocumentPart\"\r\n\r\n"
                + "------=mhtDocumentPart\r\n"
                + "Content-Type: text/html; charset=\"utf-8\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "Content-Location: file:///C:/fake/document.html\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)) + "\r\n\r\n"
                + "------=mhtDocumentPart--\r\n";

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "word/document.xml", DOCUMENT);
            put(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
            put(zip, "word/afchunk.mht", mht);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // 123456789 → 123-456-789 (azonosítatlan betegnél "–")
    private static String taj(String taj) {
        if (taj == null || taj.length() != 9) {
            return "–";
        }
        return taj.substring(0, 3) + "-" + taj.substring(3, 6) + "-" + taj.substring(6);
    }
}
