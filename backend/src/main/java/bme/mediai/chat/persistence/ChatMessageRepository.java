package bme.mediai.chat.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // a beteg legutóbbi kérdései, a legfrissebb elöl
    List<ChatMessage> findTop50ByPatientIdOrderByRequestedAtDescIdDesc(Long patientId);

    boolean existsByPatientIdAndStatus(Long patientId, ChatStatus status);

    // Sorzár: a callbackek (retrieve, result) egymás után, a friss állapotot látva futnak
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ChatMessage m WHERE m.id = :id")
    Optional<ChatMessage> findByIdForUpdate(@Param("id") Long id);
}
