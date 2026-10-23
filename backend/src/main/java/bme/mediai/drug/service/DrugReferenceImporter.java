package bme.mediai.drug.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// A referenciaadat frissítése: letöltés → beolvasás → ATC → DDInter-hatóanyag leképezés → a táblák
// cseréje EGY tranzakcióban (a szabály közben a régi, utána az új adatot látja, félkészet soha).
// Végül esemény: a szabálymotor minden betegnél újraszámol (DrugReferenceRefreshedEvent).
@Service
public class DrugReferenceImporter {

    private static final Logger LOG = LoggerFactory.getLogger(DrugReferenceImporter.class);
    private static final int BATCH = 2000;

    private final DrugReferenceDownloader downloader;
    private final RxNormAtcLookup rxNorm;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public DrugReferenceImporter(DrugReferenceDownloader downloader, RxNormAtcLookup rxNorm, JdbcTemplate jdbc,
            TransactionTemplate tx, ApplicationEventPublisher events) {
        this.downloader = downloader;
        this.rxNorm = rxNorm;
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
    }

    public record Result(List<String> notes) {
    }

    record AtcMapping(String atc, String status, Map<Integer, String> ddiOrigins) {
    }

    public Result run() throws IOException {
        List<String> notes = new ArrayList<>();
        Path puphaZip = downloader.pupha(notes);
        List<Path> ddinterFiles = downloader.ddinter(notes);

        PuphaReader.Content pupha = PuphaReader.read(puphaZip);
        DdinterReader.Content ddinter = DdinterReader.read(ddinterFiles);
        if (pupha.products().isEmpty() || ddinter.interactions().isEmpty()) {
            throw new IOException("Üres referenciaadat (PUPHA: " + pupha.products().size() + " készítmény, DDInter: "
                    + ddinter.interactions().size() + " pár) — a régi adat marad");
        }

        // a forgalomban lévő készítmények ATC-kódjai elöl: ezekhez kérdezünk RxNormot
        Set<String> atcInUse = new TreeSet<>();
        pupha.products().stream().filter(PuphaReader.Product::onMarket)
                .map(PuphaReader.Product::atcCode).filter(a -> a != null).forEach(atcInUse::add);
        Map<String, List<String>> rx = rxNorm.names(atcInUse, downloader.directory().resolve("rxnorm-atc.tsv"), notes);

        Map<String, Integer> ddiByKey = new HashMap<>();
        for (DdinterReader.Drug d : ddinter.drugs()) {
            ddiByKey.putIfAbsent(DrugNames.key(d.name()), d.id());
        }
        List<AtcMapping> mappings = new ArrayList<>();
        for (PuphaReader.Atc atc : pupha.atcCodes()) {
            mappings.add(map(atc, rx.getOrDefault(atc.code(), List.of()), ddiByKey));
        }

        String puphaSha = sha256(puphaZip);
        String ddinterSha = sha256(ddinterFiles);
        tx.executeWithoutResult(status -> replaceTables(pupha, ddinter, mappings));
        tx.executeWithoutResult(status -> {
            record("PUPHA", pupha.version() + " (" + puphaZip.getFileName() + ")", puphaSha, pupha.products().size(), null);
            record("DDINTER", "DDInter 2.0", ddinterSha, ddinter.interactions().size(),
                    ddinter.drugs().size() + " hatóanyag");
            long full = mappings.stream().filter(m -> m.status().equals("FULL")).count();
            long partial = mappings.stream().filter(m -> m.status().equals("PARTIAL")).count();
            long onMarketMapped = atcInUse.stream().filter(a -> mappings.stream()
                    .anyMatch(m -> m.atc().equals(a) && !m.status().equals("NONE"))).count();
            String coverage = "forgalomban lévő ATC-kódok: " + atcInUse.size() + ", ebből DDInter-adattal: "
                    + onMarketMapped + "; teljes: " + full + ", részleges: " + partial;
            notes.add(coverage);
            record("ATC_MAP", "ATC-név + RxNorm + kézi szinonima", null,
                    mappings.stream().mapToInt(m -> m.ddiOrigins().size()).sum(), coverage);
        });
        LOG.info("Gyógyszer-referencia frissítve: {}", notes);
        events.publishEvent(new DrugReferenceRefreshedEvent());
        return new Result(notes);
    }

    // Az ATC angol nevének minden része (kombinációnál több), a magyar név és az RxNorm-nevek →
    // DDInter-hatóanyag. FULL: az angol név vagy az RxNorm minden része illeszkedett.
    static AtcMapping map(PuphaReader.Atc atc, List<String> rxNames, Map<String, Integer> ddiByKey) {
        Map<Integer, String> found = new LinkedHashMap<>();
        if (atc.code().length() != 7) {
            return new AtcMapping(atc.code(), "NONE", found);
        }
        boolean enAll = matchAll(DrugNames.parts(atc.nameEn()), ddiByKey, found, "ATC_NAME");
        matchAll(DrugNames.parts(atc.nameHu()), ddiByKey, found, "ATC_NAME");
        List<String> rxParts = rxNames.stream().flatMap(n -> DrugNames.parts(n).stream()).toList();
        boolean rxAll = matchAll(rxParts, ddiByKey, found, "RXNORM");
        String status = found.isEmpty() ? "NONE" : enAll || rxAll ? "FULL" : "PARTIAL";
        return new AtcMapping(atc.code(), status, found);
    }

    private static boolean matchAll(List<String> parts, Map<String, Integer> ddiByKey, Map<Integer, String> found,
            String origin) {
        if (parts.isEmpty()) {
            return false;
        }
        boolean all = true;
        for (String part : parts) {
            List<String> keys = DrugNames.candidateKeys(part);
            Integer hit = null;
            String how = origin;
            for (int i = 0; i < keys.size() && hit == null; i++) {
                hit = ddiByKey.get(keys.get(i));
                if (hit != null && DrugNames.viaAlias(part, keys.get(i))) {
                    how = "ALIAS";
                }
            }
            if (hit == null) {
                all = false;
            } else {
                found.putIfAbsent(hit, how);
            }
        }
        return all;
    }

    private void replaceTables(PuphaReader.Content pupha, DdinterReader.Content ddinter, List<AtcMapping> mappings) {
        jdbc.execute("DELETE FROM atc_ddi_drug");
        jdbc.execute("DELETE FROM ddi_interaction");
        jdbc.execute("DELETE FROM ddi_drug");
        jdbc.execute("DELETE FROM atc_code");
        jdbc.execute("DELETE FROM pupha_product");

        batch("INSERT INTO pupha_product (id, name, brand, brand_key, atc_code, ingredient_hu, registration_no, packaging, on_market)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", pupha.products(), p -> new Object[] {
                    p.id(), cut(p.name(), 512), cut(p.brand(), 255), cut(p.brandKey(), 255), cut(p.atcCode(), 16),
                    cut(p.ingredientHu(), 512), cut(p.registrationNo(), 64), cut(p.packaging(), 512), p.onMarket() });

        Map<String, String> status = new HashMap<>();
        mappings.forEach(m -> status.put(m.atc(), m.status()));
        Set<String> seen = new TreeSet<>();
        List<PuphaReader.Atc> atcs = pupha.atcCodes().stream().filter(a -> seen.add(a.code())).toList();
        batch("INSERT INTO atc_code (code, name_hu, name_en, name_key, name_en_key, mapping) VALUES (?, ?, ?, ?, ?, ?)",
                atcs, a -> new Object[] { cut(a.code(), 16), cut(a.nameHu(), 512), cut(a.nameEn(), 512),
                    cut(DrugNames.key(a.nameHu()), 512), cut(DrugNames.key(a.nameEn()), 512), status.get(a.code()) });

        batch("INSERT INTO ddi_drug (id, name, name_key) VALUES (?, ?, ?)", ddinter.drugs(),
                d -> new Object[] { d.id(), cut(d.name(), 255), cut(DrugNames.key(d.name()), 255) });
        batch("INSERT INTO ddi_interaction (drug_a, drug_b, level) VALUES (?, ?, ?)", ddinter.interactions(),
                i -> new Object[] { i.drugA(), i.drugB(), i.level() });

        List<Object[]> links = new ArrayList<>();
        Set<String> linkSeen = new TreeSet<>();
        for (AtcMapping m : mappings) {
            m.ddiOrigins().forEach((ddi, origin) -> {
                if (linkSeen.add(m.atc() + "|" + ddi)) {
                    links.add(new Object[] { m.atc(), ddi, origin });
                }
            });
        }
        batch("INSERT INTO atc_ddi_drug (atc_code, ddi_drug_id, origin) VALUES (?, ?, ?)", links, l -> l);
    }

    private <T> void batch(String sql, List<T> rows, java.util.function.Function<T, Object[]> args) {
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<Object[]> chunk = rows.subList(from, Math.min(rows.size(), from + BATCH)).stream().map(args).toList();
            jdbc.batchUpdate(sql, chunk);
        }
    }

    private void record(String source, String version, String sha, int rows, String note) {
        jdbc.update("INSERT INTO drug_reference_import (source, version_label, sha256, row_count, note) VALUES (?, ?, ?, ?, ?)",
                source, cut(version, 128), sha, rows, note);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(Path file) throws IOException {
        return sha256(List.of(file));
    }

    private static String sha256(List<Path> files) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (Path f : files) {
                try (InputStream in = Files.newInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        md.update(buf, 0, n);
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
