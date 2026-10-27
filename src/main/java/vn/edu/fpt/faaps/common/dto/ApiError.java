package vn.edu.fpt.faaps.common.dto;

public record ApiError(boolean success, String code, String message) {
    public static ApiError of(String code, String message) {
        return new ApiError(false, code, message);
    }
}
