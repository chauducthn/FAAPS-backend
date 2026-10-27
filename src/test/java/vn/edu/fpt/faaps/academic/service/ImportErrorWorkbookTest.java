package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.ImportRow;

class ImportErrorWorkbookTest {
    @Test
    void exportsOnlyInvalidTranscriptRows() throws Exception {
        var rows = List.of(
                new ImportRow(2, "PRF192", "FA2022", BigDecimal.TEN, "PASSED", 1, null, null, null),
                new ImportRow(3, "BAD", "FA2022", null, "PASSED", 1, null, null, "Course not found"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(ImportErrorWorkbook.transcript(rows)))) {
            assertEquals(2, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("Course not found", workbook.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());
        }
    }

    @Test
    void exportsCourseValidationMessages() {
        var row = new AdminCourseService.ImportRowItem(2, "BAD", "Invalid", 0, null,
                "Unknown", null, null, "ERROR", List.of("Credits must be between 1 and 30"));
        assertTrue(ImportErrorWorkbook.courses(List.of(row)).length > 0);
    }
}
