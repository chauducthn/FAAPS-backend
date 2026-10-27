package vn.edu.fpt.faaps.service;

import vn.edu.fpt.faaps.dto.TranscriptDtos;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.dto.TranscriptDtos.*;
import vn.edu.fpt.faaps.service.TranscriptWorkbookParser;

@Service
public class TranscriptImportService {
    private final JdbcTemplate jdbc;
    private final TranscriptWorkbookParser parser;

    public TranscriptImportService(JdbcTemplate jdbc, TranscriptWorkbookParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    @Transactional
    public ImportPreview preview(UUID studentId, MultipartFile file) {
        String importId = UUID.randomUUID().toString();
        Integer studentExists = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id=?", Integer.class,
                studentId.toString());
        if (studentExists == null || studentExists == 0)
            throw new IllegalArgumentException("Your academic profile has not been assigned");
        List<ImportRow> rows = parser.parse(file);
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

}


