package bme.mediai.user.service;

import bme.mediai.user.api.UserCreateRequest;
import bme.mediai.user.api.UserResponse;
import bme.mediai.user.exception.EmailConflictException;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.exception.UsernameConflictException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse create(UserCreateRequest request) {
        if (repository.existsByUsername(request.username())) {
            throw new UsernameConflictException(request.username());
        }
        if (repository.existsByEmail(request.email())) {
            throw new EmailConflictException(request.email());
        }

        User user = new User(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.role());

        return UserResponse.from(repository.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return repository.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll(Role role) {
        List<User> users = role == null
                ? repository.findAll(Sort.by("id"))
                : repository.findByRole(role, Sort.by("id"));

        return users.stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse search(String username, String email) {
        if (username != null) {
            return repository.findByUsername(username)
                    .map(UserResponse::from)
                    .orElseThrow(() -> new UserNotFoundException("username", username));
        }

        if (email != null) {
            return repository.findByEmail(email)
                    .map(UserResponse::from)
                    .orElseThrow(() -> new UserNotFoundException("email", email));
        }

        throw new IllegalArgumentException("Adj meg felhasználónevet vagy email-címet");
    }
}
