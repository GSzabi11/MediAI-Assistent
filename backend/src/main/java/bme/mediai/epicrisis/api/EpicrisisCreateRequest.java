package bme.mediai.epicrisis.api;

import jakarta.validation.constraints.NotNull;

// Az epikrízis az ellátás kiválasztásával készül: az ellátás dokumentumaiból, felvételeiből és az aktuális
// gyógyszerekből (a leleteket az orvos nem válogatja — a felhasználó döntése, 2026-10-23)
public record EpicrisisCreateRequest(

    @NotNull(message = "The medicalCareId must not be null")
    Long medicalCareId,

    // ki kérte (bejelentkezés nélkül a kliens adja — nem bizonyító erejű)
    Long userId
) {
}
