package bme.mediai.audit.service;

import bme.mediai.audit.api.AccessLogResponse;
import bme.mediai.audit.persistence.AccessAction;
import bme.mediai.audit.persistence.AccessLog;
import bme.mediai.audit.persistence.AccessLogRepository;
import bme.mediai.user.persistence.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@Service
public class AccessLogService {

    private final AccessLogRepository repository;
    private final UserRepository userRepository;
    private final PatientResolver patientResolver;

    public AccessLogService(AccessLogRepository repository, UserRepository userRepository,
            PatientResolver patientResolver) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.patientResolver = patientResolver;
    }

    // A HTTP-kérés adataiból egy naplósor. A beteget ebben a sorrendben keresi:
    // ?patientId= paraméter → az út (pl. /api/documents/5) → létrehozásnál a Location fejléc.
    @Transactional
    public void record(String userIdHeader, String method, String path, String patientIdParam,
            String location, int httpStatus, String remoteAddr) {
        Long userId = knownUserId(userIdHeader);
        Long patientId = parseLong(patientIdParam);
        if (patientId == null) {
            patientId = patientResolver.resolve(path)
                    .or(() -> patientResolver.resolve(location))
                    .orElse(null);
        }
        AccessLog log = new AccessLog(userId, patientId, actionOf(method, path),
                truncate(method + " " + path, 255), httpStatus, ipOf(remoteAddr));
        repository.save(log);
    }

    @Transactional(readOnly = true)
    public List<AccessLogResponse> findByPatient(Long patientId) {
        return repository.findByPatientIdOrderByAccessedAtDescIdDesc(patientId)
                .stream()
                .map(AccessLogResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccessLogResponse> findByUser(Long userId) {
        return repository.findByUserIdOrderByAccessedAtDescIdDesc(userId)
                .stream()
                .map(AccessLogResponse::from)
                .toList();
    }

    private static AccessAction actionOf(String method, String path) {
        if (method.equals("GET") || method.equals("HEAD")) {
            // A nyers fájl letöltése kivitel, nem csak megtekintés
            return path.endsWith("/file") ? AccessAction.EXPORT : AccessAction.READ;
        }
        // A keresés olvasás; csak azért POST, mert a TAJ/név nem mehet query stringbe (CLAUDE.md)
        if (path.endsWith("/search")) {
            return AccessAction.READ;
        }
        return AccessAction.WRITE;
    }

    // Bejelentkezés még nincs: az X-User-Id fejlécben megadott, LÉTEZŐ felhasználó, különben NULL
    private Long knownUserId(String header) {
        Long id = parseLong(header);
        return id != null && userRepository.existsById(id) ? id : null;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // A getRemoteAddr() mindig IP-literál, így nincs DNS-lekérdezés
    private static InetAddress ipOf(String remoteAddr) {
        try {
            return remoteAddr == null ? null : InetAddress.getByName(remoteAddr);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
