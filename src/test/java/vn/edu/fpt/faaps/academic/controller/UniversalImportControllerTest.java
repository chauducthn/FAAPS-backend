package vn.edu.fpt.faaps.academic.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import vn.edu.fpt.faaps.academic.service.AdminCourseService;
import vn.edu.fpt.faaps.academic.service.CurriculumImportService;
import vn.edu.fpt.faaps.academic.service.TranscriptImportService;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;

class UniversalImportControllerTest {
    private TranscriptImportService transcriptImports;
    private AdminCourseService courseService;
    private CurriculumImportService curriculumImports;
    private UniversalImportController controller;

    @BeforeEach
    void setUp() {
        transcriptImports = mock(TranscriptImportService.class);
        courseService = mock(AdminCourseService.class);
        curriculumImports = mock(CurriculumImportService.class);
        controller = new UniversalImportController(transcriptImports, courseService, curriculumImports);
    }

    @Test
    void rejectsNonExcelExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", new byte[]{1, 2, 3});

        assertThrows(UniversalImportController.InvalidFileFormatException.class, () ->
                controller.preview(new AuthSession(UUID.randomUUID(), "Admin", "admin@fpt.edu.vn", "ADMIN", "ACTIVE"),
                        file, "COURSE", null));
    }

    @Test
    void rejectsInvalidMimeTypeEvenIfExtensionIsXlsx() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.xlsx", "text/javascript", new byte[]{1, 2, 3});

        assertThrows(UniversalImportController.InvalidFileFormatException.class, () ->
                controller.preview(new AuthSession(UUID.randomUUID(), "Admin", "admin@fpt.edu.vn", "ADMIN", "ACTIVE"),
                        file, "COURSE", null));
    }

    @Test
    void rejectsStudentAccessToCourseImport() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "courses.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        assertThrows(AccessDeniedException.class, () ->
                controller.preview(new AuthSession(UUID.randomUUID(), "Student", "student@fpt.edu.vn", "STUDENT", "ACTIVE"),
                        file, "COURSE", null));
    }

    @Test
    void requiresTargetIdForCurriculumImport() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "curriculum.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});

        assertThrows(IllegalArgumentException.class, () ->
                controller.preview(new AuthSession(UUID.randomUUID(), "Admin", "admin@fpt.edu.vn", "ADMIN", "ACTIVE"),
                        file, "CURRICULUM", null));
    }

    @Test
    void formatsInvalidFileFormatExceptionNicely() {
        var ex = new UniversalImportController.InvalidFileFormatException("Invalid file format");
        ResponseEntity<ApiError> response = controller.handleInvalidFormat(ex);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().success());
        assertEquals("INVALID_FILE_FORMAT", response.getBody().code());
    }
}
