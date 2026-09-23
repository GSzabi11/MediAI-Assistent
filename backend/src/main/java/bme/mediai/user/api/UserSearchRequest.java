package bme.mediai.user.api;

public record UserSearchRequest(
        String username,
        String email
) {}
