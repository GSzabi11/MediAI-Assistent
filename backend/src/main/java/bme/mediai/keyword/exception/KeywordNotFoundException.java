package bme.mediai.keyword.exception;

public class KeywordNotFoundException extends RuntimeException {

    public KeywordNotFoundException(Long id) {
        super("Nem található kulcsszó ezzel az azonosítóval: " + id);
    }
}
