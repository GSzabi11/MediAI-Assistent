package bme.mediai.user.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.user.persistence.Role;
import bme.mediai.user.service.UserService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request) {
        UserResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/users/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<UserResponse> findAll(@RequestParam(required = false) Role role) {
        return service.findAll(role);
    }

    @PostMapping("/search")
    public UserResponse search(@RequestBody UserSearchRequest request) {
        return service.search(request.username(), request.email());
    }
}
