package bme.mediai.user.api;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

    @NotBlank(message = "The username must not be blank")
    String username,

    @NotBlank(message = "The password must not be blank")
    String password
)
{}
