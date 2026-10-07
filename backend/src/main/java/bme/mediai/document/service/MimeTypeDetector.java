package bme.mediai.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

// A MIME-típust a fájl tartalmából állapítjuk meg, nem a kliens Content-Type fejlécéből.
final class MimeTypeDetector {

    static final String PDF_MIME = "application/pdf";

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private MimeTypeDetector() {
    }

    // null, ha nem támogatott típus
    static String detect(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(8);
        }
        if (startsWith(head, PDF)) {
            return PDF_MIME;
        }
        if (startsWith(head, PNG)) {
            return "image/png";
        }
        if (startsWith(head, JPEG)) {
            return "image/jpeg";
        }
        return isValidUtf8(file) ? "text/plain" : null;
    }

    static String extension(String mimeType) {
        return switch (mimeType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".txt";
        };
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        return head.length >= magic.length && Arrays.equals(head, 0, magic.length, magic, 0, magic.length);
    }

    private static boolean isValidUtf8(Path file) throws IOException {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file)));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
