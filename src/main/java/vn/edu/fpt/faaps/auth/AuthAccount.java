package vn.edu.fpt.faaps.auth;

import java.util.UUID;

public record AuthAccount(
        UUID id,
        String email,
        String fullName,
        String role,
        String status,
        String passwordHash) {
}
