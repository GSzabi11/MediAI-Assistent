package bme.mediai.research.exception;

// Nincs ilyen kutatás / kutatási összefoglaló / cikk (404)
public class ResearchNotFoundException extends RuntimeException {

    public ResearchNotFoundException(String what, Long id) {
        super("Nem található " + what + " ezzel az azonosítóval: " + id);
    }
}
