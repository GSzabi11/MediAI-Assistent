package bme.mediai.research.api;

import java.time.OffsetDateTime;

// A beteg könyvjelzős cikke, azzal a kereséssel, amelyben az orvos megjelölte
public record BookmarkResponse(ResearchArticleResponse article, Long researchId, String researchQuery,
        OffsetDateTime researchCreatedAt) {
}
