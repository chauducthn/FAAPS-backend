package vn.edu.fpt.faaps.academic.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.ImportRow;

public final class ImportErrorWorkbook {
    private ImportErrorWorkbook() {}

    public static byte[] transcript(List<ImportRow> rows) {
        return workbook("Transcript errors", List.of("Row", "Course", "Semester", "Score", "Status", "Attempt", "Error"),
                rows.stream().filter(row -> row.error() != null).map(row -> values(
                        row.rowNumber(), text(row.courseCode()), text(row.semesterCode()),
                        row.finalScore() == null ? "" : row.finalScore(), text(row.status()),
                        row.attemptNumber(), row.error())).toList());
    }

    public static byte[] courses(List<AdminCourseService.ImportRowItem> rows) {
        return workbook("Course errors", List.of("Row", "Code", "Name", "Credits", "Category", "Errors"),
                rows.stream().filter(row -> !row.errors().isEmpty()).map(row -> values(
                        row.rowNumber(), text(row.code()), text(row.name()), text(row.credits()),
                        text(row.categoryName()), String.join("; ", row.errors()))).toList());
    }

    private static byte[] workbook(String name, List<String> headers, List<List<Object>> rows) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(name);
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) header.createCell(i).setCellValue(headers.get(i));
            for (int r = 0; r < rows.size(); r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < rows.get(r).size(); c++) row.createCell(c).setCellValue(String.valueOf(rows.get(r).get(c)));
            }
            for (int i = 0; i < headers.size(); i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create error workbook", exception);
        }
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static List<Object> values(Object... values) { return java.util.Arrays.asList(values); }
}
