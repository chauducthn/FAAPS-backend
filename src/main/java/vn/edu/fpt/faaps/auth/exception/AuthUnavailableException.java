package vn.edu.fpt.faaps.auth.exception;

public class AuthUnavailableException extends RuntimeException {
    public AuthUnavailableException() {
        super("Account storage is not configured");
    }
}
