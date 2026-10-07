package bme.mediai.rule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import bme.mediai.lab.persistence.LabResultRepository;

// Indulási pótlás: a szabálymotor bevezetése előtt rögzített (és minden más módon bekerült)
// laborsorokra is lefut. Idempotens — betegenként külön tranzakció, egy beteg hibája nem állítja
// meg a többit.
@Component
public class RuleBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(RuleBackfill.class);

    private final LabResultRepository labResultRepository;
    private final LabReferenceRangeRule rule;

    public RuleBackfill(LabResultRepository labResultRepository, LabReferenceRangeRule rule) {
        this.labResultRepository = labResultRepository;
        this.rule = rule;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        for (Long patientId : labResultRepository.findPatientIdsWithResults()) {
            try {
                rule.reconcile(patientId);
            } catch (RuntimeException e) {
                LOG.error("Referencia-szabály pótlása sikertelen, beteg {}", patientId, e);
            }
        }
    }
}
