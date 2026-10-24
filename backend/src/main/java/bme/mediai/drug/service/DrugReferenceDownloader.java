package bme.mediai.drug.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// A referenciafájlok letöltése (PUPHA zip, DDInter CSV-k) a mediai.drugs.download-dir alá.
// Kifelé csak a fájlkérés megy — betegadat nem. Ha a letöltés nem sikerül (pl. nincs internet), a
// korábban letöltött vagy kézzel odamásolt fájlokból dolgozunk; ha ilyen sincs, a frissítés elbukik.
@Component
class DrugReferenceDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(DrugReferenceDownloader.class);

    // "PUPHA_20261101_v3.zip" — a "_kozos" (közös, OEP-intézményi) változat nem kell
    private static final Pattern PUPHA_ZIP = Pattern.compile("PUPHA_(\\d{8})_v(\\d+)\\.zip");
    // A letöltési oldal csak 8 csoportot hivatkoz, de a szerveren mind a 14 ATC-főcsoport fájlja megvan
    // (C, G, J, M, N, S nélkül pl. a béta-blokkoló × antiretrovirális párok hiányoznának)
    static final List<String> DDINTER_GROUPS = List.of("A", "B", "C", "D", "G", "H", "J", "L", "M", "N", "P", "R", "S", "V");

    // A NEAK tanúsítványa a Microsec e-Szigno Root CA 2009-re vezet, ami a JDK alap-truststore-jában
    // nincs benne (a Windowséban igen). Ezt az egy gyökeret a csomagban hozzuk (resources/certs,
    // SHA-256 3C:5F:81:FE:…:F3:78, a Windows-tárolóval egyeztetve) — a JDK gyökerei mellé, nem helyettük.
    private static final String EXTRA_ROOT = "/certs/microsec-e-szigno-root-ca-2009.pem";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .sslContext(sslContext())
            .build();
    private final Path dir;
    private final String puphaListingUrl;
    private final String ddinterBaseUrl;

    DrugReferenceDownloader(@Value("${mediai.drugs.download-dir}") String dir,
            @Value("${mediai.drugs.pupha-listing-url}") String puphaListingUrl,
            @Value("${mediai.drugs.ddinter-base-url}") String ddinterBaseUrl) {
        this.dir = Path.of(dir);
        this.puphaListingUrl = puphaListingUrl;
        this.ddinterBaseUrl = ddinterBaseUrl.endsWith("/") ? ddinterBaseUrl : ddinterBaseUrl + "/";
    }

    Path directory() {
        return dir;
    }

    // A legfrissebb végleges PUPHA zip: a NEAK letöltési oldaláról a legnagyobb dátum + változat
    Path pupha(List<String> notes) throws IOException {
        Path target = dir.resolve("pupha");
        Files.createDirectories(target);
        try {
            String listing = get(puphaListingUrl);
            Matcher m = PUPHA_ZIP.matcher(listing);
            String best = null;
            while (m.find()) {
                if (best == null || rank(m.group()).compareTo(rank(best)) > 0) {
                    best = m.group();
                }
            }
            if (best == null) {
                throw new IOException("A NEAK oldalán nincs PUPHA_ééééhhnn_vN.zip hivatkozás");
            }
            Path file = target.resolve(best);
            if (Files.exists(file)) {
                notes.add("PUPHA: " + best + " már le van töltve");
                return file;
            }
            URI base = URI.create(puphaListingUrl);
            // A NEAK fájlkiszolgálója a .zip után "1"-et vár (ahogy az oldal is hivatkozik rá)
            URI uri = base.resolve("/pfile/file?path=/letoltheto/ATFO_dok/gyogyszer/PUPHA/" + best + "1&inline=true");
            download(uri, file);
            notes.add("PUPHA letöltve: " + best);
            return file;
        } catch (IOException | RuntimeException e) {
            Path local = latestLocal(target).orElseThrow(() -> new IOException(
                    "A PUPHA letöltése nem sikerült, és helyi fájl sincs (" + target + "): " + e.getMessage(), e));
            notes.add("PUPHA letöltése nem sikerült (" + e.getMessage() + ") — helyi fájl: " + local.getFileName());
            LOG.warn("PUPHA letöltése nem sikerült, helyi fájlból: {}", local, e);
            return local;
        }
    }

    // A DDInter nyolc CSV-je; hiba esetén a meglévő helyi példányok
    List<Path> ddinter(List<String> notes) throws IOException {
        Path target = dir.resolve("ddinter");
        Files.createDirectories(target);
        List<Path> files = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String g : DDINTER_GROUPS) {
            String name = "ddinter_downloads_code_" + g + ".csv";
            Path file = target.resolve(name);
            try {
                download(URI.create(ddinterBaseUrl + name), file);
            } catch (IOException | RuntimeException e) {
                failed.add(g);
                LOG.warn("DDInter {} letöltése nem sikerült", name, e);
            }
            if (!Files.exists(file)) {
                throw new IOException("A DDInter " + name + " letöltése nem sikerült, és helyi példány sincs (" + target + ")");
            }
            files.add(file);
        }
        if (!failed.isEmpty()) {
            notes.add("DDInter letöltése nem sikerült (" + String.join(", ", failed) + ") — a korábbi helyi példányból");
        }
        return files;
    }

    private static String rank(String fileName) {
        Matcher m = PUPHA_ZIP.matcher(fileName);
        m.matches();
        return m.group(1) + String.format("%04d", Integer.parseInt(m.group(2)));
    }

    private static Optional<Path> latestLocal(Path target) throws IOException {
        try (Stream<Path> s = Files.list(target)) {
            return s.filter(p -> PUPHA_ZIP.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparing(p -> rank(p.getFileName().toString())));
        }
    }

    private String get(String url) throws IOException {
        try {
            HttpResponse<String> r = http.send(request(URI.create(url)), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IOException(url + " → HTTP " + r.statusCode());
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("megszakítva", e);
        }
    }

    // Ideiglenes fájlba, majd áthelyezés: félbeszakadt letöltés nem írja felül a jó helyi példányt
    private void download(URI uri, Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        try {
            HttpResponse<InputStream> r = http.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    throw new IOException(uri + " → HTTP " + r.statusCode());
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(tmp) == 0) {
                throw new IOException(uri + " → üres válasz");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("megszakítva", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static SSLContext sslContext() {
        try {
            TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int n = 0;
            for (TrustManager tm : defaults.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) {
                    for (X509Certificate c : x.getAcceptedIssuers()) {
                        store.setCertificateEntry("jdk-" + n++, c);
                    }
                }
            }
            try (InputStream in = DrugReferenceDownloader.class.getResourceAsStream(EXTRA_ROOT)) {
                if (in == null) {
                    throw new IllegalStateException("Hiányzik: " + EXTRA_ROOT);
                }
                store.setCertificateEntry("microsec-2009", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("A letöltő TLS-beállítása nem sikerült", e);
        }
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
    }
}
