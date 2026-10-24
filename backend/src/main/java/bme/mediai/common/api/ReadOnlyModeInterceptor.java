package bme.mediai.common.api;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Informálódás mód (docs/care-context.md): ha a kérés fejlécében {@code X-Access-Mode: READ_ONLY}
 * áll, minden nem olvasó /api hívást 403-mal elutasít, kivéve az engedélylistát (keresés, chat,
 * Klinikai státusz, ad hoc interakció-ellenőrzés, kórtörténet-készítés, szakirodalmi kutatás).
 *
 * Bejelentkezés nélkül ez csak a véletlen módosítás ellen véd: a fejlécet a kliens küldi,
 * elhagyásával a tiltás megkerülhető (ahogy az X-User-Id is bármire átírható).
 */
@Component
public class ReadOnlyModeInterceptor implements HandlerInterceptor {

    public static final String MODE_HEADER = "X-Access-Mode";
    public static final String READ_ONLY = "READ_ONLY";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private record Allowed(String method, Pattern path) {
        static Allowed of(String method, String regex) {
            return new Allowed(method, Pattern.compile(regex));
        }
    }

    // Az út a context-path nélkül, query string nélkül; a Python callbackjei nem küldik a fejlécet
    private static final List<Allowed> ALLOWED = List.of(
            Allowed.of("POST", "/api/auth/login"),
            Allowed.of("POST", "/api/[a-z-]+/search"),
            Allowed.of("POST", "/api/chat-messages"),
            Allowed.of("POST", "/api/clinical-summaries"),
            Allowed.of("POST", "/api/patients/\\d+/drug-interactions/check"),
            Allowed.of("POST", "/api/medical-histories"),
            // Dokumentum-feltöltés (multipart) — ideiglenes, amíg nincs EESZT-import: így vihetők be
            // régi leletek új ellátás nyitása nélkül. A többi dokumentum-írás (szöveg, darabok, újra-
            // feldolgozás) továbbra is tiltott.
            Allowed.of("POST", "/api/documents"),
            Allowed.of("POST", "/api/research-contexts"),
            Allowed.of("PUT", "/api/research-contexts/\\d+/edited-text"),
            Allowed.of("POST", "/api/research"),
            Allowed.of("POST", "/api/research/\\d+/(ranking|evaluations)"),
            Allowed.of("PATCH", "/api/research/\\d+/articles/\\d+"));

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!READ_ONLY.equalsIgnoreCase(request.getHeader(MODE_HEADER))) return true;
        String method = request.getMethod();
        if (READ_METHODS.contains(method)) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean allowed = ALLOWED.stream()
                .anyMatch(a -> a.method().equals(method) && a.path().matcher(path).matches());
        // A kivételt a GlobalExceptionHandler alakítja ProblemDetail-lé (403)
        if (!allowed) throw new ReadOnlyModeException();
        return true;
    }
}
