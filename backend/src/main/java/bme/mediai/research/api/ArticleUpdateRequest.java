package bme.mediai.research.api;

// Az orvos döntése egy cikkről; a null mező nem változik
public record ArticleUpdateRequest(Boolean excluded, Boolean bookmarked) {
}
