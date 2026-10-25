package vn.edu.fpt.faaps.student;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TranscriptImportService {
    private static final Set<String> STATUSES = Set.of("IN_PROGRESS", "PASSED", "FAILED", "WITHDRAWN", "EXEMPTED");
    private final JdbcTemplate jdbc;

    public TranscriptImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ImportPreview preview(UUID studentId, MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5_000_000 ||
                file.getOriginalFilename() == null || file.getOriginalFilename().length() > 255 ||
                !file.getOriginalFilename().toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("Choose an .xlsx file up to 5 MB");
        }
        String importId = UUID.randomUUID().toString();
        Integer studentExists = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id=?", Integer.class,
                studentId.toString());
        if (studentExists == null || studentExists == 0)
            throw new IllegalArgumentException("Your academic profile has not been assigned");
        List<ImportRow> rows = new ArrayList<>();
        Set<String> rowKeys = new HashSet<>();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook book = new XSSFWorkbook(file.getInputStream())) {
            if (book.getNumberOfSheets() == 0) throw new IllegalArgumentException("Workbook has no sheet");
            var sheet = book.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || !"CourseCode".equalsIgnoreCase(value(formatter, header, 0))
                    || !"SemesterCode".equalsIgnoreCase(value(formatter, header, 1))
                    || !"FinalScore".equalsIgnoreCase(value(formatter, header, 2))
                    || !"Status".equalsIgnoreCase(value(formatter, header, 3))) {
                throw new IllegalArgumentException("Columns must start with CourseCode, SemesterCode, FinalScore, Status, AttemptNumber");
            }
            if (sheet.getLastRowNum() > 1000) throw new IllegalArgumentException("Maximum 1000 transcript rows per file");
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row source = sheet.getRow(index);
                if (source == null) continue;
                String courseCode = value(formatter, source, 0);
                String semesterCode = value(formatter, source, 1);
                String scoreText = value(formatter, source, 2);
                String status = value(formatter, source, 3).toUpperCase();
                String attemptText = value(formatter, source, 4);
                if (courseCode.isBlank() && semesterCode.isBlank() && scoreText.isBlank() && status.isBlank()) continue;
                if (courseCode.length() > 50 || semesterCode.length() > 50 || status.length() > 20)
                    throw new IllegalArgumentException("CourseCode, SemesterCode or Status is too long at row " + (index + 1));
                String error = null;
                BigDecimal score = null;
                int attempt = 1;
                try {
                    if (!scoreText.isBlank()) score = new BigDecimal(scoreText);
                    if (!attemptText.isBlank()) attempt = Integer.parseInt(attemptText);
                    if (score != null && (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.TEN) > 0))
                        error = "FinalScore must be between 0 and 10";
                    if (attempt < 1) error = "AttemptNumber must be positive";
                } catch (NumberFormatException exception) {
                    error = "FinalScore or AttemptNumber is invalid";
                }
                if (Set.of("PASSED", "FAILED").contains(status) && score == null)
                    error = "FinalScore is required for passed or failed courses";
                if (!STATUSES.contains(status)) error = "Status is invalid";
                String courseId = idFor("SELECT id FROM courses WHERE code=?", courseCode);
                String semesterId = idFor("SELECT id FROM semesters WHERE code=?", semesterCode);
                if (courseId == null) error = "CourseCode not found";
                if (semesterId == null) error = "SemesterCode not found";
                if (!rowKeys.add(courseCode.toUpperCase() + "|" + semesterCode.toUpperCase() + "|" + attempt))
                    error = "Duplicate course, semester and attempt in this file";
                rows.add(new ImportRow(index + 1, courseCode, semesterCode, score, status, attempt,
                        courseId, semesterId, error));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read the .xlsx file", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) throw exception;
            throw new IllegalArgumentException("File is not a valid .xlsx workbook");
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Workbook has no transcript rows");
        long errors = rows.stream().filter(row -> row.error() != null).count();
        jdbc.update("""
                INSERT INTO student_transcript_imports(id,student_id,file_name,status,row_count,error_count)
                VALUES (?,?,?,'PREVIEW',?,?)
                """, importId, studentId.toString(), file.getOriginalFilename(), rows.size(), errors);
        for (ImportRow row : rows) {
            jdbc.update("""
                    INSERT INTO student_transcript_import_rows
                    (id,import_id,line_number,course_id,semester_id,course_code,semester_code,
                     final_score,status,attempt_number,validation_error)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), importId, row.rowNumber(), row.courseId(),
                    row.semesterId(), row.courseCode(), row.semesterCode(), row.finalScore(), row.status(),
                    row.attemptNumber(), row.error());
        }
        return new ImportPreview(importId, rows.size(), (int) errors, rows);
    }

    @Transactional
    public ImportResult confirm(UUID studentId, UUID importId) {
        if (jdbc.query("SELECT user_id FROM students WHERE user_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), studentId.toString()).isEmpty())
            throw new IllegalArgumentException("Your academic profile has not been assigned");
        List<String> state = jdbc.query("""
                SELECT status FROM student_transcript_imports WHERE id=? AND student_id=? FOR UPDATE
                """, (rs, row) -> rs.getString(1), importId.toString(), studentId.toString());
        if (state.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found");
        if (!"PREVIEW".equals(state.get(0))) throw new IllegalArgumentException("Import already confirmed");
        List<ImportRow> rows = rows(importId);
        if (rows.stream().anyMatch(row -> row.error() != null)) throw new IllegalArgumentException("Resolve preview errors before confirming");
        for (ImportRow row : rows) {
            Integer credits = jdbc.queryForObject("SELECT credits FROM courses WHERE id=?", Integer.class, row.courseId());
            int earned = Set.of("PASSED", "EXEMPTED").contains(row.status()) ? credits : 0;
            List<String> existing = jdbc.query("""
                    SELECT id FROM student_course_records
                    WHERE student_id=? AND course_id=? AND semester_id=? AND attempt_number=?
                    FOR UPDATE
                    """, (rs, index) -> rs.getString(1), studentId.toString(), row.courseId(), row.semesterId(), row.attemptNumber());
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO student_course_records
                        (id,student_id,course_id,semester_id,final_score,status,attempt_number,credits_earned)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), studentId.toString(), row.courseId(), row.semesterId(),
                        row.finalScore(), row.status(), row.attemptNumber(), earned);
            } else {
                jdbc.update("""
                        UPDATE student_course_records SET final_score=?,status=?,credits_earned=?,updated_at=CURRENT_TIMESTAMP
                        WHERE id=?
                        """, row.finalScore(), row.status(), earned, existing.get(0));
            }
        }
        jdbc.update("""
                UPDATE students s SET total_credits_earned=(
                  SELECT COALESCE(SUM(earned),0) FROM (
                    SELECT MAX(credits_earned) earned FROM student_course_records
                    WHERE student_id=? GROUP BY course_id
                  ) distinct_courses), updated_at=CURRENT_TIMESTAMP WHERE s.user_id=?
                """, studentId.toString(), studentId.toString());
        jdbc.update("""
                UPDATE students s SET current_gpa=(
                  SELECT COALESCE(ROUND(SUM(r.final_score*c.credits)/NULLIF(SUM(c.credits),0),2),0)
                  FROM student_course_records r JOIN courses c ON c.id=r.course_id
                  WHERE r.student_id=? AND r.final_score IS NOT NULL
                    AND r.status IN ('PASSED','FAILED')
                ), updated_at=CURRENT_TIMESTAMP WHERE s.user_id=?
                """, studentId.toString(), studentId.toString());
        List<String> affectedSemesters = jdbc.query("SELECT DISTINCT semester_id FROM student_course_records WHERE student_id=?",
                (rs, index) -> rs.getString(1), studentId.toString());
        for (String semesterId : affectedSemesters) {
            List<String> summaries = jdbc.query("SELECT id FROM semester_summaries WHERE student_id=? AND semester_id=?",
                    (rs, index) -> rs.getString(1), studentId.toString(), semesterId);
            if (summaries.isEmpty()) {
                jdbc.update("INSERT INTO semester_summaries(id,student_id,semester_id) VALUES (?,?,?)",
                        UUID.randomUUID().toString(), studentId.toString(), semesterId);
            }
            jdbc.update("""
                    UPDATE semester_summaries SET
                      total_credits_enrolled=(SELECT COALESCE(SUM(c.credits),0)
                        FROM student_course_records r JOIN courses c ON c.id=r.course_id
                        WHERE r.student_id=? AND r.semester_id=?),
                      total_credits_earned=(SELECT COALESCE(SUM(r.credits_earned),0)
                        FROM student_course_records r WHERE r.student_id=? AND r.semester_id=?),
                      semester_gpa=(SELECT COALESCE(ROUND(SUM(r.final_score*c.credits)/NULLIF(SUM(c.credits),0),2),0)
                        FROM student_course_records r JOIN courses c ON c.id=r.course_id
                        WHERE r.student_id=? AND r.semester_id=? AND r.final_score IS NOT NULL
                          AND r.status IN ('PASSED','FAILED')),
                      cumulative_gpa=(SELECT COALESCE(ROUND(SUM(r.final_score*c.credits)/NULLIF(SUM(c.credits),0),2),0)
                        FROM student_course_records r JOIN courses c ON c.id=r.course_id
                        JOIN semesters completed ON completed.id=r.semester_id
                        JOIN semesters target ON target.id=?
                        WHERE r.student_id=? AND completed.start_date<=target.start_date
                          AND r.final_score IS NOT NULL AND r.status IN ('PASSED','FAILED')),
                      updated_at=CURRENT_TIMESTAMP WHERE student_id=? AND semester_id=?
                    """, studentId.toString(), semesterId, studentId.toString(), semesterId,
                    studentId.toString(), semesterId, semesterId, studentId.toString(),
                    studentId.toString(), semesterId);
        }
        jdbc.update("UPDATE student_transcript_imports SET status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP WHERE id=?",
                importId.toString());
        return new ImportResult(importId.toString(), rows.size(), "CONFIRMED");
    }

    public List<ImportHistory> history(UUID studentId) {
        return jdbc.query("""
                SELECT id,file_name,status,row_count,error_count,created_at,confirmed_at
                FROM student_transcript_imports WHERE student_id=? ORDER BY created_at DESC,id DESC
                """, (rs, row) -> new ImportHistory(rs.getString("id"), rs.getString("file_name"),
                rs.getString("status"), rs.getInt("row_count"), rs.getInt("error_count"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toLocalDateTime()),
                studentId.toString());
    }

    public ImportPreview detail(UUID studentId, UUID importId) {
        List<Integer> owned = jdbc.query("SELECT row_count FROM student_transcript_imports WHERE id=? AND student_id=?",
                (rs, row) -> rs.getInt(1), importId.toString(), studentId.toString());
        if (owned.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found");
        List<ImportRow> rows = rows(importId);
        return new ImportPreview(importId.toString(), rows.size(), (int) rows.stream().filter(row -> row.error() != null).count(), rows);
    }

    private List<ImportRow> rows(UUID importId) {
        return jdbc.query("""
                SELECT line_number,course_code,semester_code,final_score,status,attempt_number,
                       course_id,semester_id,validation_error
                FROM student_transcript_import_rows WHERE import_id=? ORDER BY line_number
                """, (rs, row) -> new ImportRow(rs.getInt("line_number"), rs.getString("course_code"),
                rs.getString("semester_code"), rs.getBigDecimal("final_score"), rs.getString("status"),
                rs.getInt("attempt_number"), rs.getString("course_id"), rs.getString("semester_id"),
                rs.getString("validation_error")), importId.toString());
    }

    private String idFor(String query, String code) {
        return jdbc.query(query, (rs, row) -> rs.getString(1), code).stream().findFirst().orElse(null);
    }

    private String value(DataFormatter formatter, Row row, int column) {
        return row.getCell(column) == null ? "" : formatter.formatCellValue(row.getCell(column)).strip();
    }

    public record ImportRow(int rowNumber, String courseCode, String semesterCode, BigDecimal finalScore,
            String status, int attemptNumber, String courseId, String semesterId, String error) {}
    public record ImportPreview(String importId, int rowCount, int errorCount, List<ImportRow> rows) {}
    public record ImportResult(String importId, int importedCount, String status) {}
    public record ImportHistory(String id, String fileName, String status, int rowCount, int errorCount,
            java.time.LocalDateTime createdAt, java.time.LocalDateTime confirmedAt) {}
}
