package vn.edu.fpt.faaps.user.service;

public final class UserExceptions {
    private UserExceptions() {}

    public static class NotFound extends RuntimeException {
        public NotFound() {}
        public NotFound(String message) { super(message); }
    }

    public static class Conflict extends RuntimeException {
        public Conflict() {}
        public Conflict(String message) { super(message); }
    }
}
