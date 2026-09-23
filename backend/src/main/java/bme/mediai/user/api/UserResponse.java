package bme.mediai.user.api;

import java.time.OffsetDateTime;

import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;

public record UserResponse(
        Long id,
        String username,
        String email,
        Role role,
        OffsetDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getUsername(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
