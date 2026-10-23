package bme.mediai.recordingfinding.api;

import jakarta.validation.constraints.NotNull;

// Törlés és újrakinyerés: csak a felhasználó (a userId a kérésből jön — még nincs bejelentkezés)
public record FindingUserRequest(

    @NotNull(message = "The userId must not be null")
    Long userId
) {
}
