package bme.mediai.voiceprofile.api;

import java.util.List;

// Spring → Python: POST /profiles. Abszolút utak (a két szolgáltatás egy gépen fut). A Python a
// mondatokból kiszűrt beszédet outputWavPath-ra írja (16 kHz mono WAV) — ebből számolható újra a
// hanglenyomat modellváltáskor, felvétel nélkül.
public record VoiceEnrollmentJobRequest(
        Long profileId,
        List<String> sentencePaths,
        String noisePath,
        String outputWavPath,
        String callbackUrl
) {
}
