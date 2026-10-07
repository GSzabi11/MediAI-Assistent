package bme.mediai.document.exception;

import bme.mediai.document.persistence.DocumentStatus;

// A feldolgozás eredménye csak PROCESSING dokumentumra érkezhet, és csak egyszer
public class DocumentNotProcessingException extends RuntimeException {

    public DocumentNotProcessingException(Long id, DocumentStatus status) {
        super("A(z) " + id + " dokumentum nincs feldolgozás alatt (állapot: " + status
                + "); az eredmény már megérkezett, vagy nem automatikusan feldolgozott dokumentum");
    }
}
