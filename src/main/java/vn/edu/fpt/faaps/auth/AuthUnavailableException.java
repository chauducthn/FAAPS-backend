package vn.edu.fpt.faaps.auth;

public class AuthUnavailableException extends RuntimeException {
    public AuthUnavailableException() {
        super("Account storage is not configured");
    }
}
