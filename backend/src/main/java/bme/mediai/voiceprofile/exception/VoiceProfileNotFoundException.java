package bme.mediai.voiceprofile.exception;

public class VoiceProfileNotFoundException extends RuntimeException {

    public VoiceProfileNotFoundException(Long userId) {
        super("Nincs aktív hangprofil ehhez a felhasználóhoz: " + userId);
    }

    private VoiceProfileNotFoundException(String message) {
        super(message);
    }

    public static VoiceProfileNotFoundException byId(Long id) {
        return new VoiceProfileNotFoundException("Nem található hangprofil ezzel az azonosítóval: " + id);
    }
}
