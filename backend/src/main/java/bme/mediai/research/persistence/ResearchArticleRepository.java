package bme.mediai.research.persistence;

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

    List<ResearchArticle> findByResearchIdAndExcludedFalseOrderByRankAsc(Long researchId, Pageable pageable);

    List<ResearchArticle> findByResearchIdAndIdIn(Long researchId, List<Long> ids);

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
}
