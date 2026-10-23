package bme.mediai.research.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Egy talált cikk (V30). A bibliográfiai adat a forrásból jön és nem változik; az értékelés
// (összefoglaló, relevancia) a modelltől, cikkenként; a kivétel és a könyvjelző az orvos döntése.
@Entity
@Table(name = "research_article")
public class ResearchArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long researchId;

    @Column(updatable = false)
    private int rank;

    @Column(updatable = false)
    private String pmid;

    @Column(updatable = false)
    private String doi;

    @Column(updatable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<String> authors;

    @Column(updatable = false)
    private String journal;

    @Column(updatable = false)
    private Integer pubYear;

    @Column(name = "abstract", updatable = false)
    private String abstractText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<ResearchSource> sources;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<String> publicationTypes;

    @Column(updatable = false)
    private Integer citationCount;

    @Enumerated(EnumType.STRING)
    private ArticleEvalStatus evalStatus;

    private String summary;

    @Enumerated(EnumType.STRING)
    private Relevance relevance;

    private String relevanceReason;

    private String evalError;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> evalWarnings;

    private String evalModel;

    private Long evalSeed;

    private String evalPromptHash;

    private OffsetDateTime evaluatedAt;

    private boolean excluded;

    private boolean bookmarked;

    // AI-rangsor (V32): előszűrés, a modell választása (1 = legrelevánsabb), és a számolt sorrend
    @Enumerated(EnumType.STRING)
    private Relevance screenRelevance;

    private Integer aiPick;

    private String aiPickReason;

    private Integer aiRank;

    protected ResearchArticle() {
    }

    public ResearchArticle(Long researchId, int rank, String pmid, String doi, String title, List<String> authors,
            String journal, Integer pubYear, String abstractText, List<ResearchSource> sources,
            List<String> publicationTypes, Integer citationCount) {
        this.researchId = researchId;
        this.rank = rank;
        this.pmid = pmid;
        this.doi = doi;
        this.title = title;
        this.authors = List.copyOf(authors);
        this.journal = journal;
        this.pubYear = pubYear;
        this.abstractText = abstractText;
        this.sources = List.copyOf(sources);
        this.publicationTypes = publicationTypes == null ? List.of() : List.copyOf(publicationTypes);
        this.citationCount = citationCount;
        this.evalStatus = ArticleEvalStatus.NONE;
    }

    // Értékelés kérése: NONE / FAILED, vagy a beragadt (régóta PENDING) cikk; true, ha kérni kell
    public boolean markPending(OffsetDateTime staleBefore) {
        boolean stale = evalStatus == ArticleEvalStatus.PENDING && evaluatedAt != null
                && evaluatedAt.isBefore(staleBefore);
        if (evalStatus != ArticleEvalStatus.NONE && evalStatus != ArticleEvalStatus.FAILED && !stale) {
            return false;
        }
        this.evalStatus = ArticleEvalStatus.PENDING;
        this.evalError = null;
        // PENDING-nél a kérés ideje (a beragadás felismeréséhez); DONE-nál az értékelésé
        this.evaluatedAt = OffsetDateTime.now();
        return true;
    }

    public void completeEvaluation(String summary, Relevance relevance, String reason, List<String> warnings,
            String model, Long seed, String promptHash) {
        if (evalStatus != ArticleEvalStatus.PENDING) {
            throw new IllegalStateException("A(z) " + id + " cikk értékelése nem vár eredményre");
        }
        this.summary = summary;
        this.relevance = relevance;
        this.relevanceReason = reason;
        this.evalWarnings = List.copyOf(warnings);
        this.evalModel = model;
        this.evalSeed = seed;
        this.evalPromptHash = promptHash;
        this.evalStatus = ArticleEvalStatus.DONE;
        this.evaluatedAt = OffsetDateTime.now();
    }

    public void failEvaluation(String error) {
        if (evalStatus != ArticleEvalStatus.PENDING) {
            return;
        }
        this.evalError = error;
        this.evalStatus = ArticleEvalStatus.FAILED;
        this.evaluatedAt = OffsetDateTime.now();
    }

    // Az előző rangsor helyére (újrarangsoroláskor minden cikkre lefut)
    public void applyRanking(Relevance screenRelevance, Integer pick, String pickReason, int aiRank) {
        if (pick != null && (screenRelevance != Relevance.HIGH || pickReason == null || pickReason.isBlank())) {
            throw new IllegalArgumentException("Csak magas előszűrésű cikk emelhető ki, indoklással");
        }
        this.screenRelevance = screenRelevance;
        this.aiPick = pick;
        this.aiPickReason = pick == null ? null : pickReason;
        this.aiRank = aiRank;
    }

    public void setExcluded(boolean excluded) {
        this.excluded = excluded;
    }

    public void setBookmarked(boolean bookmarked) {
        this.bookmarked = bookmarked;
    }

    public Long getId() {
        return id;
    }

    public Long getResearchId() {
        return researchId;
    }

    public int getRank() {
        return rank;
    }

    public String getPmid() {
        return pmid;
    }

    public String getDoi() {
        return doi;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getAuthors() {
        return authors;
    }

    public String getJournal() {
        return journal;
    }

    public Integer getPubYear() {
        return pubYear;
    }

    public String getAbstractText() {
        return abstractText;
    }

    public List<ResearchSource> getSources() {
        return sources;
    }

    public List<String> getPublicationTypes() {
        return publicationTypes;
    }

    public Integer getCitationCount() {
        return citationCount;
    }

    public ArticleEvalStatus getEvalStatus() {
        return evalStatus;
    }

    public String getSummary() {
        return summary;
    }

    public Relevance getRelevance() {
        return relevance;
    }

    public String getRelevanceReason() {
        return relevanceReason;
    }

    public String getEvalError() {
        return evalError;
    }

    public List<String> getEvalWarnings() {
        return evalWarnings;
    }

    public String getEvalModel() {
        return evalModel;
    }

    public Long getEvalSeed() {
        return evalSeed;
    }

    public String getEvalPromptHash() {
        return evalPromptHash;
    }

    public OffsetDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public boolean isExcluded() {
        return excluded;
    }

    public Relevance getScreenRelevance() {
        return screenRelevance;
    }

    public Integer getAiPick() {
        return aiPick;
    }

    public String getAiPickReason() {
        return aiPickReason;
    }

    public Integer getAiRank() {
        return aiRank;
    }

    public boolean isBookmarked() {
        return bookmarked;
    }
}
