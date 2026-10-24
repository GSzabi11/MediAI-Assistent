package bme.mediai.processing.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import bme.mediai.recording.api.RecordingJobRequest;
import bme.mediai.recordingfinding.api.ExtractionJobRequest;
import bme.mediai.recordingsummary.api.RecordingSummaryJobRequest;
import bme.mediai.voiceprofile.api.VoiceEnrollmentJobRequest;

// Spring → Python hangszolgáltatás (ai-service/voice/service.py). Ugyanaz a minta, mint az
// AiServiceClient-nél: a Python csak sorba állít és azonnal válaszol, az eredmény callbackkel jön.
// A hangfájl útja megy át, nem a hang: a két szolgáltatás ugyanazon a gépen fut.
@Component
public class VoiceServiceClient {

    private final RestClient restClient;
    private final InternalTokenVerifier token;
    private final String callbackBaseUrl;

    public VoiceServiceClient(@Value("${mediai.voice-service.url}") String baseUrl,
            @Value("${mediai.ai-service.callback-base-url}") String callbackBaseUrl,
            InternalTokenVerifier token) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.token = token;
        this.callbackBaseUrl = callbackBaseUrl.replaceAll("/+$", "");
    }

    public String callbackUrl(String path) {
        return callbackBaseUrl + path;
    }

    // RestClientException, ha a szolgáltatás nem érhető el vagy nem 2xx-et ad
    public void submitEnrollment(VoiceEnrollmentJobRequest job) {
        post("/profiles", job);
    }

    public void submitRecording(RecordingJobRequest job) {
        post("/recordings", job);
    }

    public void submitRecordingSummary(RecordingSummaryJobRequest job) {
        post("/recording-summaries", job);
    }

    public void submitRecordingExtraction(ExtractionJobRequest job) {
        post("/recording-findings", job);
    }

    public record Health(List<String> pending) {
    }

    // A Python sorában álló / futó feladatok kulcsa ("recording:5"), ld. AiServiceClient.pendingJobs.
    // Nem érhető el → üres halmaz; régi verzió (nincs "pending") → Optional.empty().
    public Optional<Set<String>> pendingJobs() {
        try {
            Health health = restClient.get().uri("/health").retrieve().body(Health.class);
            return health == null || health.pending() == null ? Optional.empty()
                    : Optional.of(Set.copyOf(health.pending()));
        } catch (RestClientException e) {
            return Optional.of(Set.of());
        }
    }

    private void post(String uri, Object body) {
        restClient.post()
                .uri(uri)
                .header(InternalTokenVerifier.HEADER, token.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
