package bme.mediai.audit.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Egy API-útból (pl. /api/documents/5/file) kideríti, melyik beteg adatát érinti.
// A táblanevek fix listából jönnek, nem a kérésből — nincs SQL-injekció.
@Component
class PatientResolver {

    private static final Pattern RESOURCE = Pattern.compile("^/api/([a-z-]+)/(\\d+)(/.*)?$");

    // URL-szegmens → tábla, amelyiknek van patient_id oszlopa
    private static final Map<String, String> TABLES = Map.of(
            "documents", "document",
            "recordings", "recording",
            "statements", "statement",
            "medical-cares", "medical_care",
            "lab-results", "lab_result",
            "medications", "medication",
            "allergies", "allergy",
            "clinical-summaries", "clinical_summary",
            "research", "research",
            "research-contexts", "research_context");
    // a chat-messages nincs itt: V25 óta nincs táblája, a controller kérés-attribútumban adja meg
    // a beteget (AccessLogInterceptor.PATIENT_ATTRIBUTE)

    private final JdbcTemplate jdbc;

    PatientResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Long> resolve(String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = RESOURCE.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        String segment = m.group(1);
        long id = Long.parseLong(m.group(2));
        if (segment.equals("patients")) {
            return Optional.of(id);
        }
        String table = TABLES.get(segment);
        if (table == null) {
            return Optional.empty();
        }
        List<Long> found = jdbc.queryForList("SELECT patient_id FROM " + table + " WHERE id = ?", Long.class, id);
        return found.stream().findFirst();
    }
}
