package vn.edu.fpt.faaps.academic.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern FPT_SEMESTER = Pattern.compile("^(SP|SPRING|SU|SUMMER|FA|FALL)\\s*(\\d{2}|20\\d{2})$");

    private final JdbcTemplate jdbc;

    public TranscriptWorkbookParser(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ImportRow> parse(MultipartFile file) {
        validateFile(file);
        List<ImportRow> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook book = new XSSFWorkbook(file.getInputStream())) {
            if (book.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Workbook has no sheet");
            }
            var sheet = book.getSheetAt(0);
            Row header = sheet.getRow(0);
            validateHeader(formatter, header);
            if (sheet.getLastRowNum() > 1000) {
                throw new IllegalArgumentException("Maximum 1000 transcript rows per file");
            }
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row source = sheet.getRow(index);
                if (source == null) {
                    continue;
                }
                String course = value(formatter, source, 0);
                String semester = value(formatter, source, 1);
                String scoreText = value(formatter, source, 2);
                String status = value(formatter, source, 3).toUpperCase(Locale.ROOT);
                String attemptText = value(formatter, source, 4);
                if (course.isBlank() && semester.isBlank() && scoreText.isBlank() && status.isBlank()) {
                    continue;
                }
                rows.add(validateRow(index + 1, course, semester, scoreText, status, attemptText, keys));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read the .xlsx file", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) {
                throw e;
            }
            throw new IllegalArgumentException("File is not a valid .xlsx workbook");
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Workbook has no transcript rows");
        }
        return rows;
    }

    private void validateHeader(DataFormatter formatter, Row header) {
        if (header == null
                || !"CourseCode".equalsIgnoreCase(value(formatter, header, 0))
                || !"SemesterCode".equalsIgnoreCase(value(formatter, header, 1))
                || !"FinalScore".equalsIgnoreCase(value(formatter, header, 2))
                || !"Status".equalsIgnoreCase(value(formatter, header, 3))) {
            throw new IllegalArgumentException(
                    "Columns must start with CourseCode, SemesterCode, FinalScore, Status, AttemptNumber");
        }
    }

    ImportRow validateRow(int line, String course, String semester, String scoreText,
            String status, String attemptText, Set<String> keys) {
        String error = null;
        BigDecimal score = null;
        int attempt = 1;
        if (course.length() > 50 || semester.length() > 50 || status.length() > 20) {
            error = "CourseCode, SemesterCode or Status is too long";
        }
        try {
            if (!scoreText.isBlank()) {
                score = new BigDecimal(scoreText);
            }
            if (!attemptText.isBlank()) {
                attempt = Integer.parseInt(attemptText);
            }
            if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.TEN) > 0)) {
                error = "FinalScore must be between 0 and 10";
            }
            if (attempt < 1) {
                error = "AttemptNumber must be positive";
            }
        } catch (NumberFormatException e) {
            error = "FinalScore or AttemptNumber is invalid";
        }
        if (Set.of("PASSED", "FAILED").contains(status) && score == null) {
            error = "FinalScore is required for passed or failed courses";
        }
        if (!STATUSES.contains(status)) {
            error = "Status is invalid";
        }
        String courseId = idFor("SELECT id FROM courses WHERE code = ?", course);
        String semesterId = resolveOrCreateSemester(semester);
        if (courseId == null) {
            error = "CourseCode not found";
        }
        if (semesterId == null) {
            error = "SemesterCode not found";
        }
        if (!keys.add(course.toUpperCase() + "|" + semester.toUpperCase() + "|" + attempt)) {
            error = "Duplicate course, semester and attempt in this file";
        }
        return new ImportRow(line, course, semester, score, status, attempt, courseId, semesterId, error);
    }

    private void validateFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > 5_000_000 || name == null
                || name.length() > 255 || !name.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("Choose an .xlsx file up to 5 MB");
        }
    }

    private String resolveOrCreateSemester(String rawCode) {
        String existing = idFor("SELECT id FROM semesters WHERE UPPER(code) = ?", rawCode.toUpperCase(Locale.ROOT));
        if (existing != null) return existing;
        Matcher m = FPT_SEMESTER.matcher(rawCode.trim().toUpperCase(Locale.ROOT));
        if (!m.matches()) return null;
        String prefix = m.group(1).substring(0, 2);
        int year = m.group(2).length() == 2 ? 2000 + Integer.parseInt(m.group(2)) : Integer.parseInt(m.group(2));
        if (year < 2006 || year > 2040) return null;
        String canonical = prefix + year;
        String found = idFor("SELECT id FROM semesters WHERE UPPER(code) = ?", canonical);
        if (found != null) return found;
        String name = switch (prefix) {
            case "SP" -> "Spring " + year;
            case "SU" -> "Summer " + year;
            default -> "Fall " + year;
        };
        LocalDate start = "SP".equals(prefix) ? LocalDate.of(year, 1, 1)
                : "SU".equals(prefix) ? LocalDate.of(year, 5, 1) : LocalDate.of(year, 9, 1);
        LocalDate end = "SP".equals(prefix) ? LocalDate.of(year, 4, 30)
                : "SU".equals(prefix) ? LocalDate.of(year, 8, 31) : LocalDate.of(year, 12, 31);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
                VALUES (?, ?, ?, ?, ?, FALSE)
                """, id, canonical, name, start, end);
        return id;
    }

    private String idFor(String sql, String code) {
        return jdbc.query(sql, (rs, row) -> rs.getString(1), code).stream().findFirst().orElse(null);
    }

    private String value(DataFormatter formatter, Row row, int column) {
        return row.getCell(column) == null ? "" : formatter.formatCellValue(row.getCell(column)).strip();
    }
}
