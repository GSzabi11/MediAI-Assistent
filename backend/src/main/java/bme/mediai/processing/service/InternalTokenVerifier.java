package bme.mediai.processing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import bme.mediai.processing.exception.InvalidCallbackTokenException;

// A Spring ↔ Python hívások közös titka (mindkét irányban ugyanaz a fejléc). Amíg nincs
// Spring Security, ez tartja távol a callback-végponttól a klienseket: enélkül bárki
// "feldolgozott" laborértéket írhatna egy beteghez.
@Component
public class InternalTokenVerifier {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expected;

    public InternalTokenVerifier(@Value("${mediai.ai-service.token}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("A mediai.ai-service.token nincs beállítva");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String token) {
        // Konstans idejű összevetés: a válaszidőből ne lehessen a tokent kitalálni
        if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCallbackTokenException();
        }
    }

    String token() {
        return new String(expected, StandardCharsets.UTF_8);
    }
}
