package bme.mediai.research.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchArticleRepository extends JpaRepository<ResearchArticle, Long> {

    Page<ResearchArticle> findByResearchId(Long researchId, Pageable pageable);

    Page<ResearchArticle> findByResearchIdAndExcluded(Long researchId, boolean excluded, Pageable pageable);

    Page<ResearchArticle> findByResearchIdAndBookmarkedTrue(Long researchId, Pageable pageable);

    List<ResearchArticle> findByResearchIdAndIdIn(Long researchId, List<Long> ids);

    // A látható relevancia: a részletes értékelésé, ha kész, különben az előszűrésé (a relevance csak DONE-nál
    // van kitöltve, V30 CHECK). filter: ACTIVE | EXCLUDED | BOOKMARKED | ALL; relevance: ALL | HIGH | MEDIUM | LOW
    // | NONE (be nem sorolt); ai: true = relevancia szerint (HIGH, MEDIUM, LOW, besorolatlan; csoporton belül a
    // modell kiemelései elöl, utána a forrás sorrendje), false = a forrás sorrendje.
    // (sima sztring-összefűzés: a text block levágná a sorvégi szóközt a kulcsszavak után)
    String EFFECTIVE_RELEVANCE = "coalesce(relevance, screen_relevance)";
    String PAGE_WHERE = " WHERE research_id = :researchId"
            + " AND CASE :filter WHEN 'ACTIVE' THEN NOT excluded WHEN 'EXCLUDED' THEN excluded"
            + " WHEN 'BOOKMARKED' THEN bookmarked ELSE true END"
            + " AND (:relevance = 'ALL' OR coalesce(" + EFFECTIVE_RELEVANCE + ", 'NONE') = :relevance) ";

    @Query(value = "SELECT * FROM research_article" + PAGE_WHERE
            + " ORDER BY CASE WHEN :ai THEN CASE " + EFFECTIVE_RELEVANCE
            + " WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 WHEN 'LOW' THEN 2 ELSE 3 END ELSE 0 END,"
            + " CASE WHEN :ai THEN coalesce(ai_pick, 99) ELSE 0 END, rank",
            countQuery = "SELECT count(*) FROM research_article" + PAGE_WHERE,
            nativeQuery = true)
    Page<ResearchArticle> findPage(@Param("researchId") Long researchId, @Param("filter") String filter,
            @Param("relevance") String relevance, @Param("ai") boolean ai, Pageable pageable);

    // A beteg összes könyvjelzős cikke, a legutóbbi keresés elöl (a "kutatási eredmény")
    @Query("""
            SELECT a FROM ResearchArticle a, Research r
             WHERE a.researchId = r.id AND r.patientId = :patientId AND a.bookmarked = true
             ORDER BY r.createdAt DESC, a.rank ASC
            """)
    List<ResearchArticle> findBookmarkedByPatient(@Param("patientId") Long patientId);

    interface Counts {
        Long getResearchId();

        long getTotal();

        long getExcluded();

        long getBookmarked();

        long getEvaluated();

        long getPending();
    }

    @Query(value = """
            SELECT research_id AS researchId, count(*) AS total,
                   count(*) FILTER (WHERE excluded) AS excluded,
                   count(*) FILTER (WHERE bookmarked) AS bookmarked,
                   count(*) FILTER (WHERE eval_status = 'DONE') AS evaluated,
                   count(*) FILTER (WHERE eval_status = 'PENDING') AS pending
              FROM research_article WHERE research_id IN (:researchIds) GROUP BY research_id
            """, nativeQuery = true)
    List<Counts> countByResearch(@Param("researchIds") List<Long> researchIds);

    // beragadt értékelések keresése (ResearchLostJobs); PENDING-nél az evaluatedAt a kérés ideje
    List<ResearchArticle> findByEvalStatusAndEvaluatedAtBefore(ArticleEvalStatus status, OffsetDateTime before);
}
