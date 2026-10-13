package bme.mediai.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// Hangfájlok a lemezen. Beanként a voice-dir (hangprofil-minták: biometrikus adat, a felolvasott
// mondatok a hanglenyomat után törlődnek); a felvételekhez a RecordingService a documents-dir-rel
// példányosítja ({patientId}/hangfelvetelek/, megmarad). Mindkettő a gitignore-olt data/ alatt.
// A formátumot a bájtokból állapítjuk meg (mint a dokumentumnál), nem a kliens Content-Type-jából.
@Component
public class AudioStorage {

    private final Path root;

    public AudioStorage(@Value("${mediai.storage.voice-dir}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relativePath) {
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Érvénytelen hangfájl-útvonal");
        }
        return path;
    }

    /** Elmenti a feltöltött hangot {@code dir/name.<ext>} néven; a relatív utat adja vissza. */
    public String save(MultipartFile file, String dir, String name) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A feltöltött hangfájl üres");
        }
        try {
            Path targetDir = Files.createDirectories(resolve(dir));
            Path temp = Files.createTempFile(targetDir, "upload-", ".tmp");
            try {
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                String ext = detectExtension(temp);
                if (ext == null) {
                    throw new IllegalArgumentException("Nem támogatott hangformátum (WebM, Ogg, WAV vagy MP4 lehet)");
                }
                Path target = targetDir.resolve(name + "." + ext);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                return root.relativize(target).toString().replace('\\', '/');
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("A hangfájl mentése nem sikerült", e);
        }
    }

    public void deleteQuietly(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException | RuntimeException ignored) {
            // a takarítás hibája ne buktassa a callbacket; legfeljebb árva fájl marad
        }
    }

    public void deleteDirectoryQuietly(String relativeDir) {
        Path dir = resolve(relativeDir);
        if (!Files.isDirectory(dir) || dir.equals(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // ld. deleteQuietly
                }
            });
        } catch (IOException ignored) {
            // ld. deleteQuietly
        }
    }

    public void deleteDirectoryContentsExcept(String relativeDir, String keepFileName) {
        Path dir = resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> !p.getFileName().toString().equals(keepFileName)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // ld. deleteQuietly
                }
            });
        } catch (IOException ignored) {
            // ld. deleteQuietly
        }
    }

    // A MediaRecorder böngészőnként mást ad: Chrome/Firefox WebM vagy Ogg (Opus), Safari MP4
    static String detectExtension(Path file) throws IOException {
        byte[] head = new byte[12];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF
                && (head[3] & 0xFF) == 0xA3) {
            return "webm";
        }
        if (n >= 4 && Arrays.equals(Arrays.copyOf(head, 4), "OggS".getBytes())) {
            return "ogg";
        }
        if (n >= 12 && Arrays.equals(Arrays.copyOf(head, 4), "RIFF".getBytes())
                && Arrays.equals(Arrays.copyOfRange(head, 8, 12), "WAVE".getBytes())) {
            return "wav";
        }
        if (n >= 8 && Arrays.equals(Arrays.copyOfRange(head, 4, 8), "ftyp".getBytes())) {
            return "m4a";
        }
        return null;
    }
}
