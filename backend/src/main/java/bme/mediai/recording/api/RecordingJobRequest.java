package bme.mediai.recording.api;

import java.util.List;

// Spring → Python: POST /recordings. A hang abszolút útja (egy gépen fut a két szolgáltatás) és az
// orvos hanglenyomata: a szerepkiosztás (ki az orvos?) ehhez hasonlít. A profilt a Spring választja
// (a felvételkor aktív), a Python nem olvas adatbázist.
public record RecordingJobRequest(
        Long recordingId,
        String audioPath,
        String mode,
        List<Float> doctorEmbedding,
        String callbackUrl
) {
}
