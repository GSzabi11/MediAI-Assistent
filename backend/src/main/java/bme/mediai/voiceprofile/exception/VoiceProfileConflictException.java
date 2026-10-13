package bme.mediai.voiceprofile.exception;

// 409: készülő profil mellé új felvétel, már lezárt profil eredménye, nem orvos felhasználó
public class VoiceProfileConflictException extends RuntimeException {

    public VoiceProfileConflictException(String message) {
        super(message);
    }
}
