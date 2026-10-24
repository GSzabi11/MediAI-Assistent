package bme.mediai.research.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import bme.mediai.processing.service.AiServiceClient;
import bme.mediai.research.persistence.ArticleEvalStatus;
import bme.mediai.research.persistence.RankingStatus;
import bme.mediai.research.persistence.Research;
import bme.mediai.research.persistence.ResearchArticle;
import bme.mediai.research.persistence.ResearchArticleRepository;
import bme.mediai.research.persistence.ResearchContext;
import bme.mediai.research.persistence.ResearchContextRepository;
import bme.mediai.research.persistence.ResearchContextStatus;
import bme.mediai.research.persistence.ResearchRepository;
import bme.mediai.research.persistence.ResearchStatus;

class ResearchLostJobsTest {

    private final ResearchContextRepository contexts = mock(ResearchContextRepository.class);
    private final ResearchRepository researches = mock(ResearchRepository.class);
    private final ResearchArticleRepository articles = mock(ResearchArticleRepository.class);
    private final ResearchContextService contextService = mock(ResearchContextService.class);
    private final ResearchService researchService = mock(ResearchService.class);
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final ResearchLostJobs lostJobs = new ResearchLostJobs(contexts, researches, articles, contextService,
            researchService, client);

    @Test
    void failsOnlyJobsMissingFromPythonQueue() {
        ResearchContext lostContext = withId(mock(ResearchContext.class), 1L);
        ResearchContext runningContext = withId(mock(ResearchContext.class), 2L);
        Research lostSearch = withId(mock(Research.class), 3L);
        Research lostRanking = withId(mock(Research.class), 4L);
        ResearchArticle lostArticle = withId(mock(ResearchArticle.class), 5L);
        ResearchArticle runningArticle = withId(mock(ResearchArticle.class), 6L);
        when(contexts.findByStatusAndRequestedAtBefore(eq(ResearchContextStatus.PROCESSING), any()))
                .thenReturn(List.of(lostContext, runningContext));
        when(researches.findByStatusAndCreatedAtBefore(eq(ResearchStatus.SEARCHING), any()))
                .thenReturn(List.of(lostSearch));
        when(researches.findByRankingStatusAndRankedAtBefore(eq(RankingStatus.PENDING), any()))
                .thenReturn(List.of(lostRanking));
        when(articles.findByEvalStatusAndEvaluatedAtBefore(eq(ArticleEvalStatus.PENDING), any()))
                .thenReturn(List.of(lostArticle, runningArticle));
        when(client.pendingJobs()).thenReturn(Optional.of(Set.of("research-context:2", "article:6")));

        lostJobs.failLostJobs();

        verify(contextService).fail(eq(1L), anyString());
        verify(contextService, never()).fail(eq(2L), anyString());
        verify(researchService).fail(eq(3L), anyString());
        verify(researchService).failRanking(eq(4L), anyString());
        verify(researchService).failEvaluations(eq(List.of(5L)), anyString());
    }

    @Test
    void doesNothingWhenPendingListUnknown() {
        ResearchContext context = withId(mock(ResearchContext.class), 1L);
        when(contexts.findByStatusAndRequestedAtBefore(any(), any())).thenReturn(List.of(context));
        when(client.pendingJobs()).thenReturn(Optional.empty());

        lostJobs.failLostJobs();

        verify(contextService, never()).fail(anyLong(), anyString());
        verify(researchService, never()).failEvaluations(anyList(), anyString());
    }

    private static <T> T withId(T entity, Long id) {
        if (entity instanceof ResearchContext c) {
            when(c.getId()).thenReturn(id);
        } else if (entity instanceof Research r) {
            when(r.getId()).thenReturn(id);
        } else if (entity instanceof ResearchArticle a) {
            when(a.getId()).thenReturn(id);
        }
        return entity;
    }
}
