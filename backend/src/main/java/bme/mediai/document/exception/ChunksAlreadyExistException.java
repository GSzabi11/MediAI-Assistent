package bme.mediai.document.exception;

public class ChunksAlreadyExistException extends RuntimeException {

    public ChunksAlreadyExistException(Long documentId) {
        super("A(z) " + documentId + " dokumentumhoz már feltöltötték a chunkokat");
    }
}
