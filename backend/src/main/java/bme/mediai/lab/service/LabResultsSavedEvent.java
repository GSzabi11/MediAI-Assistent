package bme.mediai.lab.service;

// Laborsorok kerültek be egy beteghez — a szabálymotor (rule modul) ugyanabban a tranzakcióban
// újraszámolja a beteg referencia-tartomány állításait. Esemény, hogy a lab modul ne függjön a
// szabálymotortól.
public record LabResultsSavedEvent(Long patientId) {
}
