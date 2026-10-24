package bme.mediai.common.api;

// Informálódás (csak olvasás) módban érkezett író kérés — 403 (docs/care-context.md)
public class ReadOnlyModeException extends RuntimeException {

    public ReadOnlyModeException() {
        super("Informálódás módban nem elérhető (csak olvasás). Nyisd meg a beteget egy ellátásban.");
    }
}
