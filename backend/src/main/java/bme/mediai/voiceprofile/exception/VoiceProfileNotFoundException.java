package bme.mediai.voiceprofile.exception;

public class VoiceProfileNotFoundException extends RuntimeException {

    public VoiceProfileNotFoundException(Long userId) {
        super("Nincs aktív hangprofil ehhez a felhasználóhoz: " + userId);
    }
}
