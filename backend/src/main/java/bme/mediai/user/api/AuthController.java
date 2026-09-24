package bme.mediai.user.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import bme.mediai.user.service.UserService;

// Bejelentkezés: csak a jelszót ellenőrzi és visszaadja a felhasználót. Munkamenet
// vagy token még NINCS — a többi végpont továbbra is az X-User-Id fejlécet hiszi el.
// A valódi védelem a Spring Security bekötésével jön (munkamenet/token).
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService service;

    public AuthController(UserService service) {
        this.service = service;
    }

    // POST + törzs: a jelszó sosem kerülhet URL-be
    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request) {
        return service.authenticate(request.username(), request.password());
    }
}
