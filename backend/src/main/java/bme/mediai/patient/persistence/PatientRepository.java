package bme.mediai.patient.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    Optional<Patient> findByTaj(String taj);

    List<Patient> findByNameAndBirthDateAndMotherName(String name, LocalDate birthDate, String motherName);  

    boolean existsByTaj(String taj);

}
