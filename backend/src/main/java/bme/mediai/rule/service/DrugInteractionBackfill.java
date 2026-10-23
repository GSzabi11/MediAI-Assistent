package bme.mediai.rule.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bme.mediai.drug.service.DrugReferenceRefreshedEvent;

// Az interakció-szabály teljes újraszámolása: induláskor (a szabály előtt rögzített gyógyszerekre is) és
// a referenciaadat minden frissítése után. Minden beteg, akinek aktív gyógyszere vagy interakció-állítása
// van (az utóbbi a takarításhoz kell). Betegenként külön tranzakció, egy beteg hibája nem állítja meg a többit.
@Component
public class DrugInteractionBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(DrugInteractionBackfill.class);

    private final JdbcTemplate jdbc;
    private final DrugInteractionRule rule;

    public DrugInteractionBackfill(JdbcTemplate jdbc, DrugInteractionRule rule) {
        this.jdbc = jdbc;
        this.rule = rule;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcileAll();
    }

    @EventListener
    public void onReferenceRefreshed(DrugReferenceRefreshedEvent event) {
        reconcileAll();
    }

    private void reconcileAll() {
        List<Long> patients = jdbc.queryForList("""
                SELECT patient_id FROM medication WHERE active
                UNION
                SELECT patient_id FROM statement WHERE source ->> 'type' = 'MEDICATION'
                """, Long.class);
        for (Long patientId : patients) {
            try {
                rule.reconcile(patientId);
            } catch (RuntimeException e) {
                LOG.error("Interakció-szabály pótlása sikertelen, beteg {}", patientId, e);
            }
        }
    }
}
