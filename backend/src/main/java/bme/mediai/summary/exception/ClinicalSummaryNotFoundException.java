package bme.mediai.summary.exception;

public class ClinicalSummaryNotFoundException extends RuntimeException {

    public ClinicalSummaryNotFoundException(Long id) {
        super("Nem található klinikai összefoglaló ezzel az azonosítóval: " + id);
    }
}
