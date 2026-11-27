package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.ComboOption;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.NamedOption;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.SlotCourse;

/** Stores and queries FLM Combos and Electives imported from Excel (faaps-combos.xlsx / faaps-electives.xlsx). */
@Service
public class FlmSubjectGroupService {
    private static final Pattern PREFIX_MAJOR = Pattern.compile("^([A-Z]{2,4})(?:\\d{2})?_");
    private static final Pattern CUR_MAJOR = Pattern.compile("_(?:([A-Z]{2,4})(?:-\\d{4})?)_K\\d{2}", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;

    public FlmSubjectGroupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTables();
    }

    private void ensureTables() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS flm_subject_groups (
                        id CHAR(36) PRIMARY KEY, group_type VARCHAR(16) NOT NULL, external_id VARCHAR(32) NULL,
                        group_code VARCHAR(64) NOT NULL, group_name VARCHAR(255) NOT NULL, major_code VARCHAR(32) NULL,
                        curriculum_codes TEXT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uq_flm_group (group_type, group_code))
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS flm_subject_group_courses (
                        id CHAR(36) PRIMARY KEY, group_id CHAR(36) NOT NULL, course_code VARCHAR(64) NOT NULL,
                        course_name VARCHAR(255) NOT NULL, term INT NOT NULL DEFAULT 0, slot_order INT NOT NULL DEFAULT 0,
                        note VARCHAR(255) NULL, UNIQUE KEY uq_flm_group_course (group_id, course_code))
                    """);
        } catch (RuntimeException ignored) { /* Minimal test schemas may not allow DDL. */ }
    }

    @Transactional
    public GroupImportSummary importGroups(GroupImportRequest request) {
        if (request == null || request.groups() == null || request.groups().isEmpty()) {
            return new GroupImportSummary(0, 0);
        }
        int groupsSaved = 0;
        int coursesLinked = 0;
        for (GroupInput g : request.groups()) {
            String type = "ELECTIVE".equalsIgnoreCase(clean(g.groupType())) ? "ELECTIVE" : "COMBO";
            String code = clean(g.groupCode());
            if (code == null) continue;
            String name = truncate(clean(g.groupName()) != null ? clean(g.groupName()) : code, 255);
            String major = extractMajor(code, g.curriculumCodes());
            String curCodes = clean(g.curriculumCodes());

            List<String> existing = jdbc.query(
                    "SELECT id FROM flm_subject_groups WHERE group_type = ? AND UPPER(group_code) = ?",
                    (rs, n) -> rs.getString(1), type, code.toUpperCase(Locale.ROOT));
            String groupId = existing.isEmpty() ? UUID.randomUUID().toString() : existing.get(0);
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO flm_subject_groups
                            (id, group_type, external_id, group_code, group_name, major_code, curriculum_codes)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, groupId, type, clean(g.externalId()), code, name, major, curCodes);
            } else {
                jdbc.update("""
                        UPDATE flm_subject_groups SET external_id = COALESCE(?, external_id), group_name = ?,
                            major_code = COALESCE(?, major_code), curriculum_codes = COALESCE(?, curriculum_codes),
                            updated_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, clean(g.externalId()), name, major, curCodes, groupId);
            }
            groupsSaved++;

            if (g.courses() != null && !g.courses().isEmpty()) {
                jdbc.update("DELETE FROM flm_subject_group_courses WHERE group_id = ?", groupId);
                int idx = 0;
                for (GroupCourseInput c : g.courses()) {
                    String cCode = clean(c.courseCode());
                    if (cCode == null) continue;
                    String cName = truncate(clean(c.courseName()) != null ? clean(c.courseName()) : cCode, 255);
                    int term = c.term() != null && c.term() >= 0 && c.term() <= 12 ? c.term() : 0;
                    jdbc.update("""
                            INSERT INTO flm_subject_group_courses
                                (id, group_id, course_code, course_name, term, slot_order, note)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON DUPLICATE KEY UPDATE course_name = VALUES(course_name),
                                term = VALUES(term), slot_order = VALUES(slot_order), note = VALUES(note)
                            """, UUID.randomUUID().toString(), groupId, cCode, cName, term, idx++, truncate(clean(c.note()), 255));
                    coursesLinked++;
                }
            }
        }
        return new GroupImportSummary(groupsSaved, coursesLinked);
    }

    public List<ComboOption> findCombosFor(String majorCode, String cohort) {
        try {
            String upperMajor = majorCode == null ? "" : majorCode.strip().toUpperCase(Locale.ROOT);
            List<GroupRow> rows = jdbc.query("""
                    SELECT id, group_code, group_name, major_code, curriculum_codes FROM flm_subject_groups
                    WHERE group_type = 'COMBO' AND UPPER(group_code) NOT LIKE 'PHE%' ORDER BY group_code ASC
                    """, (rs, n) -> new GroupRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)));
            if (rows.isEmpty()) return List.of();
            String upperCohort = cohort == null ? "" : cohort.strip().toUpperCase(Locale.ROOT);
            List<GroupRow> matched = new ArrayList<>();
            for (GroupRow r : rows) {
                String cc = r.curCodes() == null ? "" : r.curCodes().toUpperCase(Locale.ROOT);
                boolean majorHit = upperMajor.isEmpty() || upperMajor.equalsIgnoreCase(r.majorCode())
                        || cc.contains("_" + upperMajor + "_") || cc.contains("_" + upperMajor + "-");
                boolean cohortHit = !upperCohort.isEmpty() && cc.contains(upperCohort) && majorHit;
                if (cohortHit || majorHit) matched.add(r);
            }
            return toComboOptions(matched);
        } catch (RuntimeException ignored) { return List.of(); }
    }

    public ComboOption findComboByCode(String comboCode) {
        if (comboCode == null || comboCode.isBlank()) return null;
        try {
            List<GroupRow> rows = jdbc.query("""
                    SELECT id, group_code, group_name, major_code, curriculum_codes FROM flm_subject_groups
                    WHERE group_type = 'COMBO' AND UPPER(group_code) = ? LIMIT 1
                    """, (rs, n) -> new GroupRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)), comboCode.strip().toUpperCase(Locale.ROOT));
            List<ComboOption> opts = toComboOptions(rows);
            return opts.isEmpty() ? null : opts.get(0);
        } catch (RuntimeException ignored) { return null; }
    }

    public List<ComboOption> findPhysicalEdCombos() {
        try {
            List<GroupRow> rows = jdbc.query("""
                    SELECT id, group_code, group_name, major_code, curriculum_codes FROM flm_subject_groups
                    WHERE group_type = 'COMBO' AND UPPER(group_code) LIKE 'PHE%' ORDER BY group_code ASC
                    """, (rs, n) -> new GroupRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)));
            return toComboOptions(rows);
        } catch (RuntimeException ignored) { return List.of(); }
    }

    public List<NamedOption> findInstruments() {
        try {
            return jdbc.query("""
                    SELECT DISTINCT gc.course_code, gc.course_name FROM flm_subject_group_courses gc
                    JOIN flm_subject_groups g ON g.id = gc.group_id
                    WHERE g.group_type = 'ELECTIVE' AND UPPER(g.group_code) LIKE 'TMI%' ORDER BY gc.course_code ASC
                    """, (rs, n) -> new NamedOption(rs.getString(1), rs.getString(1) + " — " + rs.getString(2)));
        } catch (RuntimeException ignored) { return List.of(); }
    }

    private List<ComboOption> toComboOptions(List<GroupRow> groups) {
        List<ComboOption> out = new ArrayList<>();
        for (GroupRow g : groups) {
            List<SlotCourse> slots = jdbc.query("""
                    SELECT c.id, gc.course_code, COALESCE(c.name, gc.course_name), COALESCE(c.credits, 3)
                    FROM flm_subject_group_courses gc
                    LEFT JOIN courses c ON UPPER(c.code) = UPPER(gc.course_code)
                    WHERE gc.group_id = ? ORDER BY gc.term ASC, gc.slot_order ASC
                    """, (rs, n) -> new SlotCourse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                    g.id());
            if (!slots.isEmpty()) out.add(new ComboOption(g.code(), g.code() + " — " + g.name(), slots));
        }
        return out;
    }

    private static String extractMajor(String groupCode, String curCodes) {
        Matcher m = PREFIX_MAJOR.matcher(groupCode.toUpperCase(Locale.ROOT));
        if (m.find()) return m.group(1);
        if (curCodes != null) {
            Matcher cm = CUR_MAJOR.matcher(curCodes.toUpperCase(Locale.ROOT));
            if (cm.find()) return cm.group(1);
        }
        return null;
    }

    private static String clean(String v) { return v == null || v.isBlank() ? null : v.strip(); }
    private static String truncate(String v, int max) { return v == null || v.length() <= max ? v : v.substring(0, max); }

    private record GroupRow(String id, String code, String name, String majorCode, String curCodes) {}
    public record GroupCourseInput(String courseCode, String courseName, Integer term, String note) {}
    public record GroupInput(String groupType, String externalId, String groupCode, String groupName,
            String curriculumCodes, List<GroupCourseInput> courses) {}
    public record GroupImportRequest(List<GroupInput> groups) {}
    public record GroupImportSummary(int groupsSaved, int coursesLinked) {}
}
