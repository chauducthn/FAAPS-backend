package vn.edu.fpt.faaps.academic.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.ImportRow;

@Component
public class TranscriptWorkbookParser {
    private static final Set<String> STATUSES = Set.of("IN_PROGRESS", "PASSED", "FAILED", "WITHDRAWN", "EXEMPTED");

    private final JdbcTemplate jdbc;

    public TranscriptWorkbookParser(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ImportRow> parse(MultipartFile file) {
        validateFile(file);
        List<ImportRow> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Map<String, Integer> autoAttempts = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook book = new XSSFWorkbook(file.getInputStream())) {
            if (book.getNumberOfSheets() == 0) throw new IllegalArgumentException("Workbook has no sheet");
            var sheet = book.getSheetAt(0);
            int[] cols = resolveHeaderColumns(formatter, sheet.getRow(0));
            if (sheet.getLastRowNum() > 1000) throw new IllegalArgumentException("Maximum 1000 transcript rows per file");
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row source = sheet.getRow(index);
                if (source == null) continue;
                String course = value(formatter, source, cols[0]);
                String semester = value(formatter, source, cols[1]);
                String scoreText = cols[2] >= 0 ? value(formatter, source, cols[2]) : "";
                String status = normalizeStatus(cols[3] >= 0 ? value(formatter, source, cols[3]) : "");
                String attemptText = cols[4] >= 0 ? value(formatter, source, cols[4]) : "";
                if (course.isBlank() && semester.isBlank() && scoreText.isBlank() && status.isBlank()) continue;
                if (attemptText.isBlank() && !course.isBlank()) {
                    int next = autoAttempts.merge(course.toUpperCase(Locale.ROOT), 1, Integer::sum);
                    attemptText = String.valueOf(next);
                }
                rows.add(validateRow(index + 1, course, semester, scoreText, status, attemptText, keys));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read the .xlsx file", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("File is not a valid .xlsx workbook");
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Workbook has no transcript rows");
        return rows;
    }

    public byte[] buildTemplateWorkbook() {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Transcript");
            String[] headers = {"CourseCode", "SemesterCode", "FinalScore", "Status", "AttemptNumber"};
            Row h = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            String[][] samples = {
                {"PRF192", "FA2024", "8.2", "PASSED", "1"},
                {"JPD123", "SP2025", "4.0", "FAILED", "1"},
                {"JPD123", "SU2025", "7.8", "PASSED", "2"},
            };
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < samples[r].length; c++) row.createCell(c).setCellValue(samples[r][c]);
            }
            for (int i = 0; i < headers.length; i++) sheet.setColumnWidth(i, 18 * 256);
            book.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not generate template workbook", ex);
        }
    }

    private int[] resolveHeaderColumns(DataFormatter formatter, Row header) {
        if (header == null) {
            throw new IllegalArgumentException("Columns must include CourseCode, SemesterCode, FinalScore, Status");
        }
        int courseCol = -1, semCol = -1, scoreCol = -1, statusCol = -1, attemptCol = -1;
        for (int i = 0; i < header.getLastCellNum(); i++) {
            String h = value(formatter, header, i).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
            if (courseCol < 0 && Set.of("coursecode", "subjectcode", "code", "course").contains(h)) courseCol = i;
            else if (semCol < 0 && Set.of("semestercode", "semester", "term", "termcode").contains(h)) semCol = i;
            else if (scoreCol < 0 && Set.of("finalscore", "grade", "score", "mark", "average").contains(h)) scoreCol = i;
            else if (statusCol < 0 && Set.of("status", "result").contains(h)) statusCol = i;
            else if (attemptCol < 0 && Set.of("attemptnumber", "attempt", "try").contains(h)) attemptCol = i;
        }
        if (courseCol < 0 || semCol < 0 || statusCol < 0) {
            throw new IllegalArgumentException(
                    "Columns must start with CourseCode, SemesterCode, FinalScore, Status, AttemptNumber");
        }
        return new int[]{courseCol, semCol, scoreCol, statusCol, attemptCol};
    }

    static String normalizeStatus(String raw) {
        String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return switch (s) {
            case "PASS", "PASSED" -> "PASSED";
            case "FAIL", "FAILED", "NOT_PASSED", "NOT_PASS", "ATTENDANCE_FAIL", "SUSPENDED" -> "FAILED";
            case "IN_PROGRESS", "STUDYING", "LEARNING", "ONGOING" -> "IN_PROGRESS";
            case "EXEMPT", "EXEMPTED", "IS_EXEMPT" -> "EXEMPTED";
            case "WITHDRAWN", "DROPPED", "NOT_STARTED" -> "WITHDRAWN";
            default -> s;
        };
    }

    ImportRow validateRow(int line, String course, String semester, String scoreText,
            String rawStatus, String attemptText, Set<String> keys) {
        String status = normalizeStatus(rawStatus);
        String cleanScore = scoreText == null ? "" : scoreText.strip();
        if ("-".equals(cleanScore) || "N/A".equalsIgnoreCase(cleanScore)) cleanScore = "";
        String error = null;
        BigDecimal score = null;
        int attempt = 1;
        if (course.length() > 50 || semester.length() > 50 || status.length() > 20) {
            error = "CourseCode, SemesterCode or Status is too long";
        }
        try {
            if (!cleanScore.isBlank()) score = new BigDecimal(cleanScore.replace(',', '.'));
            if (!attemptText.isBlank()) attempt = Integer.parseInt(attemptText);
            if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.TEN) > 0)) error = "FinalScore must be between 0 and 10";
            if (attempt < 1) error = "AttemptNumber must be positive";
        } catch (NumberFormatException e) {
            error = "FinalScore or AttemptNumber is invalid";
        }
        if (!STATUSES.contains(status)) error = "Status is invalid";
        String upperCourse = course.strip().toUpperCase(Locale.ROOT);
        List<Object[]> courseRow = resolveCourse(upperCourse);
        String courseId = courseRow.isEmpty() ? null : (String) courseRow.get(0)[0];
        String canonicalCode = courseRow.isEmpty() ? course : (String) courseRow.get(0)[1];
        String semesterId = resolveOrCreateSemester(semester);
        if (courseId == null) error = "CourseCode not found";
        if (semesterId == null) error = "SemesterCode not found";
        if (!keys.add(canonicalCode.toUpperCase(Locale.ROOT) + "|" + semester.toUpperCase(Locale.ROOT) + "|" + attempt)) {
            error = "Duplicate course, semester and attempt in this file";
        }
        return new ImportRow(line, canonicalCode, semester, score, status, attempt, courseId, semesterId, error);
    }

    private List<Object[]> resolveCourse(String upper) {
        return TranscriptLookupResolver.resolveCourse(jdbc, upper);
    }

    private void validateFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > 5_000_000 || name == null
                || name.length() > 255 || !name.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("Choose an .xlsx file up to 5 MB");
        }
    }

    private String resolveOrCreateSemester(String rawCode) {
        return TranscriptLookupResolver.resolveOrCreateSemester(jdbc, rawCode);
    }

    private String value(DataFormatter formatter, Row row, int column) {
        return row.getCell(column) == null ? "" : formatter.formatCellValue(row.getCell(column)).strip();
    }
}
