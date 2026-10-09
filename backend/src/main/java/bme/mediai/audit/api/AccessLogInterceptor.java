package bme.mediai.audit.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import bme.mediai.audit.service.AccessLogService;

// Minden /api hívást naplóz, a válasz után (így a HTTP-státusz és a Location is ismert).
@Component
public class AccessLogInterceptor implements HandlerInterceptor {

    // Ideiglenes, amíg nincs bejelentkezés: ezután a bejelentkezett felhasználóból jön
    public static final String USER_HEADER = "X-User-Id";

    // Kérés-attribútum (Long): az érintett beteg, ha sem a ?patientId=, sem a tábla-alapú
    // útfeloldás nem adja meg — pl. a memóriában élő chat-üzeneteknél (nincs táblájuk).
    public static final String PATIENT_ATTRIBUTE = AccessLogInterceptor.class.getName() + ".patientId";

    private static final Logger LOG = LoggerFactory.getLogger(AccessLogInterceptor.class);

    private final AccessLogService service;

    public AccessLogInterceptor(AccessLogService service) {
        this.service = service;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        try {
            String patientId = request.getParameter("patientId");
            if (patientId == null && request.getAttribute(PATIENT_ATTRIBUTE) instanceof Long id) {
                patientId = id.toString();
            }
            service.record(
                    request.getHeader(USER_HEADER),
                    request.getMethod(),
                    request.getRequestURI(),
                    patientId,
                    response.getHeader(HttpHeaders.LOCATION),
                    response.getStatus(),
                    request.getRemoteAddr());
        } catch (RuntimeException e) {
            // A válasz ekkor már elment; a hiányzó naplósort legalább a szerver-logban jelezzük
            LOG.error("Hozzáférési napló írása sikertelen: {} {}", request.getMethod(), request.getRequestURI(), e);
        }
    }
}
