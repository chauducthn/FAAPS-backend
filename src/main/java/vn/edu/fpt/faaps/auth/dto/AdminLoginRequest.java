package vn.edu.fpt.faaps.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminLoginRequest(@NotBlank String email, @NotBlank String password) {}
