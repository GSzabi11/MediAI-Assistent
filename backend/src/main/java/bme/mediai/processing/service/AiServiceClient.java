package bme.mediai.processing.service;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import bme.mediai.chat.api.ChatJobRequest;
import bme.mediai.processing.api.ProcessingJobRequest;
import bme.mediai.research.api.ResearchContextJobRequest;
import bme.mediai.research.api.ResearchEvaluationJobRequest;
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

    // A keresés: a Python hívja a PubMedet (csak a keresőkifejezéssel), külön szálon, nem a GPU-sorban
    public void submitResearchSearch(ResearchSearchJobRequest job) {
        post("/research-searches", job);
    }

    // Cikkenkénti értékelés (összefoglaló + relevancia) a helyi modellel, a GPU-sorba
    public void submitResearchEvaluation(ResearchEvaluationJobRequest job) {
        post("/research-evaluations", job);
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
