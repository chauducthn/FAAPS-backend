package vn.edu.fpt.faaps.academic.service;


import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.*;

@Service
public class TranscriptImportService {
    private final JdbcTemplate jdbc;
    private final TranscriptWorkbookParser parser;
    private final TranscriptOcrParser ocrParser;

    public TranscriptImportService(JdbcTemplate jdbc, TranscriptWorkbookParser parser) { this(jdbc, parser, null); }

    @Autowired
    public TranscriptImportService(JdbcTemplate jdbc, TranscriptWorkbookParser parser, TranscriptOcrParser ocrParser) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.ocrParser = ocrParser;
    }

    @Transactional
    public ImportPreview preview(UUID studentId, MultipartFile file) {
        return storePreview(studentId, file.getOriginalFilename(), parser.parse(file));
    }

    @Transactional
    public ImportPreview previewOcr(UUID studentId, MultipartFile file) {
        if (ocrParser == null) throw new IllegalStateException("Transcript OCR parser is not available");
        return storePreview(studentId, file.getOriginalFilename(), ocrParser.parse(file));
    }

    private ImportPreview storePreview(UUID studentId, String fileName, List<ImportRow> rows) {
        String importId = UUID.randomUUID().toString();
        Integer studentExists = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id=?", Integer.class,
                studentId.toString());
        if (studentExists == null || studentExists == 0)
            throw new IllegalArgumentException("Your academic profile has not been assigned");
        long errors = rows.stream().filter(row -> row.error() != null).count();
        jdbc.update("""
                INSERT INTO student_transcript_imports(id,student_id,file_name,status,row_count,error_count)
                VALUES (?,?,?,'PREVIEW',?,?)
                """, importId, studentId.toString(), fileName, rows.size(), errors);
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
        return confirm(studentId, importId, false);
    }

    @Transactional
    public ImportResult confirm(UUID studentId, UUID importId, boolean skipErrors) {
        ensureStudentForUpdate(studentId);
        List<String> state = jdbc.query(
                "SELECT status FROM student_transcript_imports WHERE id=? AND student_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), importId.toString(), studentId.toString());
        if (state.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found");
        if (!"PREVIEW".equals(state.get(0))) throw new IllegalArgumentException("Import already confirmed");
        List<ImportRow> allRows = rows(importId);
        if (!skipErrors && allRows.stream().anyMatch(row -> row.error() != null)) {
            throw new IllegalArgumentException("Resolve preview errors before confirming");
        }
        List<ImportRow> rows = allRows.stream().filter(row -> row.error() == null).toList();
        if (rows.isEmpty()) throw new IllegalArgumentException("No valid transcript rows to import");
        applyRowsAndRecalculate(studentId, rows, false);
        jdbc.update("UPDATE student_transcript_imports SET status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP WHERE id=?",
                importId.toString());
        return new ImportResult(importId.toString(), rows.size(), "CONFIRMED");
    }

    @Transactional
    public ImportResult activate(UUID studentId, UUID importId) {
        ensureStudentForUpdate(studentId);
        List<String> state = jdbc.query(
                "SELECT status FROM student_transcript_imports WHERE id=? AND student_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), importId.toString(), studentId.toString());
        if (state.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found");
        List<ImportRow> rows = rows(importId).stream().filter(row -> row.error() == null).toList();
        if (rows.isEmpty()) throw new IllegalArgumentException("This import version has no valid rows");
        applyRowsAndRecalculate(studentId, rows, true);
        jdbc.update("UPDATE student_transcript_imports SET status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP WHERE id=?",
                importId.toString());
        return new ImportResult(importId.toString(), rows.size(), "CONFIRMED");
    }

    @Transactional
    public void deleteImport(UUID studentId, UUID importId) {
        List<String> found = jdbc.query(
                "SELECT id FROM student_transcript_imports WHERE id=? AND student_id=?",
                (rs, row) -> rs.getString(1), importId.toString(), studentId.toString());
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found");
        jdbc.update("DELETE FROM student_transcript_import_rows WHERE import_id=?", importId.toString());
        jdbc.update("DELETE FROM student_transcript_imports WHERE id=? AND student_id=?",
                importId.toString(), studentId.toString());
    }

    private void ensureStudentForUpdate(UUID studentId) {
        if (jdbc.query("SELECT user_id FROM students WHERE user_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), studentId.toString()).isEmpty()) {
            throw new IllegalArgumentException("Your academic profile has not been assigned");
        }
    }

    private void applyRowsAndRecalculate(UUID studentId, List<ImportRow> rows, boolean replaceAll) {
        TranscriptScoreRecalculator.applyRowsAndRecalculate(jdbc, studentId, rows, replaceAll);
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

    public byte[] templateWorkbook() { return parser.buildTemplateWorkbook(); }

    public byte[] errorWorkbook(UUID studentId, UUID importId) {
        ImportPreview preview = detail(studentId, importId);
        if (preview.errorCount() == 0) throw new IllegalArgumentException("Import has no validation errors");
        return ImportErrorWorkbook.transcript(preview.rows());
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


