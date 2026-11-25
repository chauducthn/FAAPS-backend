package vn.edu.fpt.faaps.common.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared format rules for identity fields accepted by several API flows. */
public final class IdentityInput {
    public static final String STUDENT_CODE_REGEX = "^[A-Z]{2}[0-9]{6}$";
    public static final String PHONE_REGEX =
            "^(?=(?:\\D*\\d){8,15}\\D*$)\\+?[0-9 .()-]{8,20}$";
    public static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private static final Pattern STUDENT_CODE = Pattern.compile(STUDENT_CODE_REGEX);
    private static final Pattern PHONE = Pattern.compile(PHONE_REGEX);
    private static final Pattern EMAIL = Pattern.compile(EMAIL_REGEX);

    private IdentityInput() {}

    public static String studentCode(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!STUDENT_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Student ID must be two letters followed by six digits");
        }
        return normalized;
    }

    public static String phone(String value) {
        String normalized = value == null || value.isBlank() ? null : value.strip();
        if (normalized != null && !PHONE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Phone must contain 8 to 15 digits and use valid phone characters");
        }
        return normalized;
    }

    public static String email(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        return normalized;
    }

    /**
     * The display name shared by every profile endpoint: trimmed, inner whitespace collapsed to single spaces.
     * Every flow that writes a name uses this, so the same user cannot end up with two spellings of their name.
     */
    public static String fullName(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (normalized.length() < 2 || normalized.length() > 255) {
            throw new IllegalArgumentException("Full name must be between 2 and 255 characters");
        }
        return normalized;
    }

    /**
     * The stored phone number: validated by {@link #phone(String)} first, then reduced to digits and an optional
     * leading plus. Reading a profile therefore returns the same shape no matter which endpoint wrote the row.
     */
    public static String compactPhone(String value) {
        String validated = phone(value);
        if (validated == null) {
            return null;
        }
        return validated.replaceAll("[\\s-]+", "");
    }

    public static boolean validRegistrationPassword(String value) {
        return value != null && value.length() >= 8 && value.length() <= 72
                && value.codePoints().anyMatch(Character::isLetter)
                && value.codePoints().anyMatch(Character::isDigit);
    }
}
