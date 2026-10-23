package bme.mediai.medicalhistory.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentDigestRepository extends JpaRepository<DocumentDigest, Long> {

    List<DocumentDigest> findByPatientId(Long patientId);

    // A lecserélendő régi kivonatok törlése, az új beszúrása előtt (UNIQUE document_id). NEM clearAutomatically:
    // a persistence context ürítése a hívó kezelt MedicalHistory-ját leválasztaná, és az eredménye elveszne.
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DocumentDigest d WHERE d.documentId IN :documentIds")
    int deleteByDocumentIds(@Param("documentIds") Collection<Long> documentIds);
}
