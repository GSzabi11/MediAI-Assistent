package bme.mediai.medication.service;

// Egy beteg gyógyszerlistája változott (új tétel, leállítás) — az interakció-szabály (rule modul)
// ugyanabban a tranzakcióban újraszámol. Esemény, hogy a medication modul ne függjön a szabálymotortól.
public record MedicationsChangedEvent(Long patientId) {
}
