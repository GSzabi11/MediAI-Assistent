package bme.mediai.research.api;

import java.util.List;

// Egy oldal a találati listából (saját record: a Spring Page JSON-alakja nem stabil API)
public record ArticlePageResponse(List<ResearchArticleResponse> content, int page, int size, long totalElements,
        int totalPages) {
}
