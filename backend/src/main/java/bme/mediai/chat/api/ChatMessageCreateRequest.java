package bme.mediai.chat.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatMessageCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    // ki kérdez (bejelentkezés nélkül a kliens adja — nem bizonyító erejű, mint a napló X-User-Id-ja)
    Long userId,

    @NotBlank(message = "The question must not be blank")
    @Size(max = 2000, message = "The question must be at most 2000 characters")
    String question,

    // a beszélgetés előző (a felületen látott) válaszai — a modell ezekből érti a visszautaló
    // kérdést ("és a vesefunkció?"). Ugyanannak a betegnek a kész válaszai lehetnek.
    @Size(max = 3, message = "At most 3 previous messages")
    List<@NotNull Long> previousMessageIds
)
{}
