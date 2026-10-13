package bme.mediai.voiceprofile.exception;

// 409: hangprofil (hanglenyomat) nélkül az orvos egyik betegnél sem indíthat felvételt —
// a szerepkiosztás (ki az orvos?) erre épül
public class VoiceProfileRequiredException extends RuntimeException {

    public VoiceProfileRequiredException(Long userId) {
        super("A(z) " + userId + " felhasználónak nincs használható hangprofilja: felvétel előtt a Profil "
                + "oldalon hangmintát kell felvenni");
    }
}
