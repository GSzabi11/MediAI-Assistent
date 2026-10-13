package bme.mediai.keyword.exception;

// 409: duplikált kifejezés, nem orvos felhasználó, még nem kész felvétel, már eldöntött javaslat
public class KeywordConflictException extends RuntimeException {

    public KeywordConflictException(String message) {
        super(message);
    }
}
