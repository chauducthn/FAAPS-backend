package vn.edu.fpt.faaps.admin;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurriculumImportService {
    private final JdbcTemplate jdbc;

    public CurriculumImportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Preview preview(InputStream input) {
        List<RowItem> items = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (var workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheetAt(0);
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                String code = formatter.formatCellValue(row.getCell(0)).strip().toUpperCase(Locale.ROOT);
                if (code.isBlank()) continue;
                List<String> errors = new ArrayList<>();
                int semester = integer(formatter.formatCellValue(row.getCell(1)), "semester", errors);
                boolean mandatory = !"NO".equalsIgnoreCase(formatter.formatCellValue(row.getCell(2)))
                        && !"FALSE".equalsIgnoreCase(formatter.formatCellValue(row.getCell(2)));
                String group = formatter.formatCellValue(row.getCell(3)).strip();
                List<String> courseIds = jdbc.query("SELECT id FROM courses WHERE UPPER(code)=? AND status='ACTIVE'",
                        (rs, n) -> rs.getString(1), code);
                if (courseIds.isEmpty()) errors.add("Active course not found");
                items.add(new RowItem(index + 1, code, courseIds.isEmpty() ? null : courseIds.get(0),
                        semester, mandatory, group.isBlank() ? null : group, errors));
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not read XLSX file", exception);
        }
        long valid = items.stream().filter(item -> item.errors().isEmpty()).count();
        return new Preview(items.size(), (int) valid, items.size() - (int) valid, items);
    }

    @Transactional
    public int confirm(UUID curriculumId, List<RowItem> items) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_versions WHERE id=?",
                Integer.class, curriculumId.toString());
        if (exists == null || exists == 0) throw new AdminCurriculumService.NotFound("Curriculum not found");
        if (items == null || items.isEmpty() || items.stream().anyMatch(item -> !item.errors().isEmpty()))
            throw new IllegalArgumentException("Only a non-empty validated preview can be imported");
        int imported = 0;
        for (RowItem item : items) {
            if (item.semesterNumber() < 1 || item.semesterNumber() > 9)
                throw new IllegalArgumentException("Semester must be between 1 and 9");
            Integer activeCourse = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM courses WHERE id=? AND UPPER(code)=? AND status='ACTIVE'",
                    Integer.class, item.courseId(), item.courseCode().strip().toUpperCase(Locale.ROOT));
            if (activeCourse == null || activeCourse == 0)
                throw new IllegalArgumentException("An imported course is missing or inactive");
            imported += jdbc.update("""
                    INSERT INTO curriculum_courses
                        (id, curriculum_version_id, course_id, semester_number, is_mandatory, group_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE semester_number=VALUES(semester_number),
                        is_mandatory=VALUES(is_mandatory), group_name=VALUES(group_name), updated_at=CURRENT_TIMESTAMP
                    """, UUID.randomUUID().toString(), curriculumId.toString(), item.courseId(),
                    item.semesterNumber(), item.mandatory(), item.groupName());
        }
        return imported;
    }

    private int integer(String value, String field, List<String> errors) {
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < 1 || parsed > 9) throw new NumberFormatException();
            return parsed;
        } catch (Exception exception) {
            errors.add(field + " must be between 1 and 9");
            return 0;
        }
    }

    public record RowItem(int rowNumber, String courseCode, String courseId, int semesterNumber,
            boolean mandatory, String groupName, List<String> errors) {}
    public record Preview(int totalRows, int validRows, int errorRows, List<RowItem> items) {}
}
