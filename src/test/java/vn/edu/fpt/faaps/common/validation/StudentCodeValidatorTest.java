package vn.edu.fpt.faaps.common.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StudentCodeValidatorTest {

    @Test
    void validatesAndNormalizesValidCodes() {
        assertEquals("SE173012", StudentCodeValidator.validateAndNormalize("se173012"));
        assertEquals("IA160343", StudentCodeValidator.validateAndNormalize("  IA160343  "));
        assertEquals("GD180123", StudentCodeValidator.validateAndNormalize("gd180123"));
        assertTrue(StudentCodeValidator.isValidFormat("SE160343"));
    }

    @Test
    void rejectsInvalidCodes() {
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize(null));
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize("   "));
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize("SE123"));
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize("S123456"));
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize("SE1234567"));
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize("SE000001"));
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize("SE230001"));
        assertThrows(IllegalArgumentException.class, () -> StudentCodeValidator.validateAndNormalize("12345678"));

        assertFalse(StudentCodeValidator.isValidFormat(null));
        assertFalse(StudentCodeValidator.isValidFormat(""));
        assertFalse(StudentCodeValidator.isValidFormat("INVALID"));
    }
}
