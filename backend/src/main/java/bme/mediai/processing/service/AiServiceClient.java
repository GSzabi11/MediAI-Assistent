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

import bme.mediai.chat.api.ChatJobRequest;
import bme.mediai.epicrisis.api.EpicrisisJobRequest;
import bme.mediai.medicalhistory.api.MedicalHistoryJobRequest;
import bme.mediai.processing.api.ProcessingJobRequest;
import bme.mediai.research.api.ResearchContextJobRequest;
import bme.mediai.research.api.ResearchEvaluationJobRequest;
import bme.mediai.research.api.ResearchRankingJobRequest;
import bme.mediai.research.api.ResearchSearchJobRequest;
import bme.mediai.summary.api.SummaryFacts;
import bme.mediai.summary.api.SummaryJobRequest;

// Spring → Python. A Spring sosem hívja az Ollamát: a modellt, a promptot és a GPU-sort
// a Python szolgáltatás kezeli, a Spring csak a feladatot adja át.
@Component
public class AiServiceClient {

    private final RestClient restClient;
    private final InternalTokenVerifier token;
    private final String callbackBaseUrl;

    public AiServiceClient(@Value("${mediai.ai-service.url}") String baseUrl,
            @Value("${mediai.ai-service.callback-base-url}") String callbackBaseUrl,
            InternalTokenVerifier token) {
        // HTTP/1.1: alapból h2c-upgrade-et kérne, amit az uvicorn nem támogat (csak figyelmeztet)
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        // A Python csak sorba állít, azonnal válaszol — a feldolgozás idejét nem itt várjuk ki
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.token = token;
        this.callbackBaseUrl = callbackBaseUrl.replaceAll("/+$", "");
    }

    // Klinikai összefoglaló: a beteg tényei mennek (fájl nincs), ugyanabba a Python-sorba
    public void submitSummary(Long summaryId, SummaryFacts facts) {
        SummaryJobRequest job = new SummaryJobRequest(summaryId,
                callbackBaseUrl + "/api/clinical-summaries/" + summaryId + "/result", facts);
        restClient.post()
                .uri("/summaries")
                .header(InternalTokenVerifier.HEADER, token.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(job)
                .retrieve()
                .toBodilessEntity();
    }

    // Erre az abszolút címre hív vissza a Python (pl. "/api/research/5/result")
    public String callbackUrl(String path) {
        return callbackBaseUrl + path;
    }

    // Szakirodalmi kutatás (docs/literature-research.md): betegállapot-összefoglaló a helyi modellel
    public void submitResearchContext(Long contextId, SummaryFacts facts) {
        post("/research-contexts", new ResearchContextJobRequest(contextId,
                callbackUrl("/api/research-contexts/" + contextId + "/result"), facts));
    }

    // Kórtörténet (docs/medical-history.md): dokumentum-kivonatok + összefoglaló + idővonal, a GPU-sorba
    public void submitMedicalHistory(MedicalHistoryJobRequest job) {
        post("/medical-histories", job);
    }

    // Epikrízis egy ellátáshoz (docs/epicrisis.md): kivonatok + megállapítások + javaslatok, a GPU-sorba
    public void submitEpicrisis(EpicrisisJobRequest job) {
        post("/epicrises", job);
    }

    public record Health(List<String> pending) {
    }

    // A Python sorában álló / futó feladatok kulcsa ("medical-history:5"). A sor csak memóriában él:
    // leálláskor / újraindításkor a feladat elveszik, a callback sosem jön — ebből ismeri fel a Spring a
    // beragadt feladatot. Nem érhető el → üres halmaz (nála semmi nem fut); régi verzió (nincs "pending"
    // mezője) → Optional.empty(): nem tudjuk, ne nyúljunk a feladathoz.
    public Optional<Set<String>> pendingJobs() {
        try {
            Health health = restClient.get().uri("/health").retrieve().body(Health.class);
            return health == null || health.pending() == null ? Optional.empty()
                    : Optional.of(Set.copyOf(health.pending()));
        } catch (RestClientException e) {
            return Optional.of(Set.of());
        }
    }

    // A keresés: a Python hívja a PubMedet (csak a keresőkifejezéssel), külön szálon, nem a GPU-sorban
    public void submitResearchSearch(ResearchSearchJobRequest job) {
        post("/research-searches", job);
    }

    // Cikkenkénti értékelés (összefoglaló + relevancia) a helyi modellel, a GPU-sorba
    public void submitResearchEvaluation(ResearchEvaluationJobRequest job) {
        post("/research-evaluations", job);
    }

    // Az összes találat AI-rangsora (előszűrés + a legrelevánsabb), egy feladat a GPU-sorban
    public void submitResearchRanking(ResearchRankingJobRequest job) {
        post("/research-rankings", job);
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

    // Klinikai asszisztens: a kérdés + kontextus; a Python a sor elejére teszi
    public void submitChat(ChatJobRequest job) {
        restClient.post()
                .uri("/chats")
                .header(InternalTokenVerifier.HEADER, token.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(job)
                .retrieve()
                .toBodilessEntity();
    }

    // RestClientException, ha a szolgáltatás nem érhető el vagy nem 2xx-et ad
    public void submit(Long documentId, String filePath) {
        ProcessingJobRequest job = new ProcessingJobRequest(documentId, filePath,
                callbackBaseUrl + "/api/documents/" + documentId + "/processing-result");
        restClient.post()
                .uri("/jobs")
                .header(InternalTokenVerifier.HEADER, token.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(job)
                .retrieve()
                .toBodilessEntity();
    }
}
