package vn.edu.fpt.faaps.common.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdentityInputTest {
    @Test
    void acceptsCurrentStudentCodeFormats() {
        assertEquals("SE233333", IdentityInput.studentCode(" se233333 "));
        assertEquals("IA173012", IdentityInput.studentCode("IA173012"));
        assertThrows(IllegalArgumentException.class, () -> IdentityInput.studentCode("SE12345"));
        assertThrows(IllegalArgumentException.class, () -> IdentityInput.studentCode("SE23-333"));
    }

    @Test
    void acceptsOptionalFormattedPhoneButRejectsPunctuationOnly() {
        assertNull(IdentityInput.phone(" "));
        assertEquals("0901234567", IdentityInput.phone(" 0901234567 "));
        assertEquals("+84 901 234 567", IdentityInput.phone("+84 901 234 567"));
        assertThrows(IllegalArgumentException.class, () -> IdentityInput.phone("........"));
        assertThrows(IllegalArgumentException.class, () -> IdentityInput.phone("1234"));
    }

    @Test
    void validatesEmailAndRegistrationPassword() {
        assertEquals("student@fpt.edu.vn", IdentityInput.email(" Student@FPT.edu.vn "));
        assertThrows(IllegalArgumentException.class, () -> IdentityInput.email("invalid@"));
        assertTrue(IdentityInput.validRegistrationPassword("Secret123"));
        assertTrue(IdentityInput.validRegistrationPassword("MậtKhẩu123"));
        assertEquals(false, IdentityInput.validRegistrationPassword("abcdefgh"));
    }
}
