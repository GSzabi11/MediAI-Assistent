package bme.mediai.drug.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// ATC-kód → RxNorm hatóanyagnév (angol, amerikai írásmód — a DDInter is ezt használja), az NLM RxNav
// nyilvános API-jával. Csak a frissítéskor fut, és KIZÁRÓLAG az ATC-kód megy ki (betegadat soha).
// Az eredmény helyi gyorsítótárba kerül (download-dir/rxnorm-atc.tsv): a következő frissítés csak az
// új ATC-kódokat kérdezi le, internet nélkül pedig a gyorsítótárból dolgozik.
@Component
class RxNormAtcLookup {

    private static final Logger LOG = LoggerFactory.getLogger(RxNormAtcLookup.class);

    private final RestClient rest;
    private final boolean enabled;

    RxNormAtcLookup(@Value("${mediai.drugs.rxnorm-url}") String baseUrl,
            @Value("${mediai.drugs.rxnorm-enabled}") boolean enabled) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.enabled = enabled;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IdGroupResponse(IdGroup idGroup) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record IdGroup(List<String> rxnormId) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PropertiesResponse(Properties properties) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Properties(String name) {
        }
    }

    // ATC (csak 7 jegyű, hatóanyag-szintű kód) → RxNorm-nevek; a hiányzókat lekérdezi, ha engedélyezett
    Map<String, List<String>> names(Collection<String> atcCodes, Path cacheFile, List<String> notes) throws IOException {
        Map<String, List<String>> cache = readCache(cacheFile);
        if (!enabled) {
            notes.add("RxNorm-lekérdezés kikapcsolva (mediai.drugs.rxnorm-enabled) — csak a gyorsítótár: "
                    + cache.size() + " ATC-kód");
            return cache;
        }
        int asked = 0;
        int failed = 0;
        for (String atc : atcCodes) {
            if (atc == null || atc.length() != 7 || cache.containsKey(atc)) {
                continue;
            }
            try {
                cache.put(atc, lookup(atc));
                asked++;
                Thread.sleep(60); // az RxNav legfeljebb 20 kérést fogad másodpercenként
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                failed++;
                if (failed == 5) {
                    LOG.warn("RxNorm nem érhető el, a lekérdezés leáll", e);
                    notes.add("RxNorm nem érhető el (" + e.getMessage() + ") — a gyorsítótárból dolgozunk");
                    break;
                }
            }
        }
        if (asked > 0) {
            writeCache(cacheFile, cache);
            notes.add("RxNorm: " + asked + " új ATC-kód lekérdezve");
        }
        return cache;
    }

    private List<String> lookup(String atc) {
        IdGroupResponse ids = rest.get()
                .uri(b -> b.path("/rxcui.json").queryParam("idtype", "ATC").queryParam("id", atc).build())
                .retrieve()
                .body(IdGroupResponse.class);
        List<String> names = new ArrayList<>();
        if (ids == null || ids.idGroup() == null || ids.idGroup().rxnormId() == null) {
            return names;
        }
        for (String rxcui : ids.idGroup().rxnormId()) {
            PropertiesResponse p = rest.get().uri("/rxcui/{id}/properties.json", rxcui)
                    .retrieve()
                    .body(PropertiesResponse.class);
            if (p != null && p.properties() != null && p.properties().name() != null) {
                names.add(p.properties().name());
            }
        }
        return names;
    }

    // Soronként: ATC <TAB> név1|név2 (üres: az RxNormban nincs ilyen ATC-kód — azt sem kérdezzük újra)
    private static Map<String, List<String>> readCache(Path file) throws IOException {
        Map<String, List<String>> cache = new TreeMap<>();
        if (!Files.exists(file)) {
            return cache;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String names = line.substring(tab + 1);
            cache.put(line.substring(0, tab), names.isEmpty() ? List.of() : List.of(names.split("\\|")));
        }
        return cache;
    }

    private static void writeCache(Path file, Map<String, List<String>> cache) throws IOException {
        Files.createDirectories(file.getParent());
        List<String> lines = new ArrayList<>();
        cache.forEach((atc, names) -> lines.add(atc + "\t" + String.join("|", names)));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
