package bme.mediai.drug.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// A frissítés indítása: havonta ütemezve (a PUPHA a hónap elején jelenik meg), kézzel az API-ról, és
// induláskor, ha még üres a referenciaadat. Egyszerre egy fut; az utolsó futás eredménye memóriában.
@Service
public class DrugReferenceRefresh {

    private static final Logger LOG = LoggerFactory.getLogger(DrugReferenceRefresh.class);

    public record LastRun(OffsetDateTime startedAt, OffsetDateTime finishedAt, boolean success, List<String> notes,
            String error) {
    }

    private final DrugReferenceImporter importer;
    private final JdbcTemplate jdbc;
    private final boolean importOnStartup;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<LastRun> lastRun = new AtomicReference<>();
    private volatile OffsetDateTime runningSince;

    public DrugReferenceRefresh(DrugReferenceImporter importer, JdbcTemplate jdbc,
            @Value("${mediai.drugs.import-on-startup}") boolean importOnStartup) {
        this.importer = importer;
        this.jdbc = jdbc;
        this.importOnStartup = importOnStartup;
    }

    public boolean isRunning() {
        return running.get();
    }

    public OffsetDateTime runningSince() {
        return runningSince;
    }

    public LastRun lastRun() {
        return lastRun.get();
    }

    // false: már fut egy frissítés
    public boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    // A hívó előbb tryStart()-tal megszerzi a futás jogát. Az API a proxyn át hívja (aszinkron); az
    // ütemező és az indulási pótlás a saját szálán, közvetlenül.
    @Async
    public void runStarted() {
        runningSince = OffsetDateTime.now();
        try {
            DrugReferenceImporter.Result r = importer.run();
            lastRun.set(new LastRun(runningSince, OffsetDateTime.now(), true, r.notes(), null));
        } catch (Exception e) {
            LOG.error("A gyógyszer-referencia frissítése nem sikerült", e);
            lastRun.set(new LastRun(runningSince, OffsetDateTime.now(), false, List.of(), e.getMessage()));
        } finally {
            runningSince = null;
            running.set(false);
        }
    }

    @Scheduled(cron = "${mediai.drugs.refresh-cron}", zone = "Europe/Budapest")
    public void monthly() {
        if (tryStart()) {
            runStarted();
        }
    }

    // @Async: az első letöltés percekig tarthat, az indulást nem tartja fel
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM ddi_interaction", Integer.class);
        if (importOnStartup && rows != null && rows == 0 && tryStart()) {
            LOG.info("A gyógyszer-referencia üres — első betöltés indul (PUPHA + DDInter)");
            runStarted();
        }
    }
}
