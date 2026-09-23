package bme.mediai.user.api;

import bme.mediai.user.persistence.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(

    @NotBlank(message = "The username must not be blank")
    @Size(max = 64, message = "The username must be at most 64 characters")
    String username,

    @NotBlank(message = "The email must not be blank")
    @Email(message = "The email must be a valid address")
    @Size(max = 255)
    String email,

    @NotBlank(message = "The password must not be blank")
    @Size(min = 8, max = 72, message = "The password must be 8-72 characters")
    String password,

    @NotNull(message = "The role must be DOCTOR or ADMIN")
    Role role
)
{}
