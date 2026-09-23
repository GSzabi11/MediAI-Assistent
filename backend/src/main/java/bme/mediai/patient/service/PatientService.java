package bme.mediai.patient.service;

import bme.mediai.patient.api.PatientCreateRequest;
import bme.mediai.patient.api.PatientResponse;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.exception.TajConflictException;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.patient.persistence.PatientRepository;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Service
public class PatientService {

    private final PatientRepository repository;

    public PatientService(PatientRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PatientResponse create(PatientCreateRequest request) {
        if (request.taj() != null && repository.existsByTaj(request.taj())) {
            throw new TajConflictException(request.taj());
        }

        Patient patient = new Patient(
                request.name(),
                request.motherName(),
                request.birthDate(),
                request.taj(),
                request.address());

        return PatientResponse.from(repository.save(patient));
    }

    @Transactional(readOnly = true)
    public PatientResponse findById(Long id) {
        return repository.findById(id)
                .map(PatientResponse::from)
                .orElseThrow(() -> new PatientNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> findAll() {
        return repository.findAll(Sort.by("id"))
                .stream()
                .map(PatientResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> search(String taj, String name, String motherName, LocalDate birthDate) {
        if (taj != null) {
            return repository.findByTaj(taj)
                    .map(PatientResponse::from)
                    .map(List::of)
                    .orElse(Collections.emptyList());
        }

        if (name != null && motherName != null && birthDate != null) {
            return repository.findByNameAndBirthDateAndMotherName(name, birthDate, motherName)
                    .stream()
                    .map(PatientResponse::from)
                    .toList();
        }

        throw new IllegalArgumentException(
                "Adj meg TAJ-t, vagy nevet, anyja nevét és születési dátumot");
    }
}