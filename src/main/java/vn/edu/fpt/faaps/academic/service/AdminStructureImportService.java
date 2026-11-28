package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminStructureImportService {
    private static final Pattern SEM_RE = Pattern.compile("^(SP|SU|FA)(\\d{4})$");
    private final JdbcTemplate jdbc;

    public AdminStructureImportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public ImportSummary importBatch(ImportRequest req) {
        Map<String, String> majorIdByKey = new HashMap<>();
        Map<String, Integer> majorLimitById = new HashMap<>();
        jdbc.query("SELECT id, code, name, COALESCE(total_credits, 0) FROM majors", rs -> {
            String id = rs.getString(1);
            majorIdByKey.put(majorKey(rs.getString(2), rs.getString(3)), id);
            majorLimitById.put(id, rs.getInt(4));
        });

        int skipped = 0;
        List<Object[]> majorInserts = new ArrayList<>();
        if (req.majors() != null) {
            for (MajorRow m : req.majors()) {
                if (m.code() == null || m.code().isBlank() || m.name() == null || m.name().isBlank()) { skipped++; continue; }
                String key = majorKey(m.code(), m.name());
                if (majorIdByKey.containsKey(key)) continue;
                String id = UUID.randomUUID().toString();
                int credits = m.totalCredits() != null && m.totalCredits() > 0 ? m.totalCredits() : 140;
                BigDecimal dur = m.durationYears() != null && m.durationYears().compareTo(BigDecimal.ZERO) > 0
                        ? m.durationYears() : new BigDecimal("4.0");
                majorInserts.add(new Object[] {
                    id, m.code().strip().toUpperCase(Locale.ROOT), m.name().strip(), "", credits, dur, normalizeMajorStatus(m.status())
                });
                majorIdByKey.put(key, id);
                majorLimitById.put(id, credits);
            }
        }
        int majorsCreated = insertIgnoringDuplicates("""
                INSERT INTO majors (id, code, name, description, total_credits, duration_years,
                    status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, majorInserts);

        Map<String, String> semIdByCode = new HashMap<>();
        jdbc.query("SELECT UPPER(code), id FROM semesters", rs -> { semIdByCode.put(rs.getString(1), rs.getString(2)); });
        Map<String, String> cohortIdByKey = new HashMap<>();
        Map<String, String> cohortMajorById = new HashMap<>();
        jdbc.query("SELECT id, major_id, version FROM curriculum_versions", rs -> {
            String cid = rs.getString(1);
            String mid = rs.getString(2);
            cohortIdByKey.put(cohortKey(mid, rs.getString(3)), cid);
            cohortMajorById.put(cid, mid);
        });

        List<Object[]> semInserts = new ArrayList<>();
        List<Object[]> cohortInserts = new ArrayList<>();
        if (req.cohorts() != null) {
            for (CohortRow c : req.cohorts()) {
                String mid = c.majorId() != null && !c.majorId().isBlank()
                        ? c.majorId() : majorIdByKey.get(majorKey(c.majorCode(), c.majorName()));
                String fromId = resolveSemester(c.effectiveFromSemesterId(), c.startSemesterCode(), semIdByCode, semInserts);
                String toId = resolveSemester(c.effectiveToSemesterId(), c.endSemesterCode(), semIdByCode, semInserts);
                if (mid == null || c.version() == null || c.version().isBlank() || fromId == null || toId == null) { skipped++; continue; }
                String ver = c.version().strip().toUpperCase(Locale.ROOT);
                String cKey = cohortKey(mid, ver);
                if (cohortIdByKey.containsKey(cKey)) continue;
                String cid = UUID.randomUUID().toString();
                String status = "INACTIVE".equalsIgnoreCase(c.status()) ? "INACTIVE" : "ACTIVE";
                boolean current = "ACTIVE".equals(status) && Boolean.TRUE.equals(c.isCurrent());
                cohortInserts.add(new Object[] { cid, mid, ver, c.majorName().strip(), fromId, toId, current, status });
                cohortIdByKey.put(cKey, cid);
                cohortMajorById.put(cid, mid);
            }
        }
        insertIgnoringDuplicates("""
                INSERT INTO semesters (id, code, name, start_date, end_date, is_current, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, semInserts);
        int cohortsCreated = insertIgnoringDuplicates("""
                INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id,
                    effective_to_semester_id, is_current, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, cohortInserts);

        List<Object[]> linkInserts = new ArrayList<>();
        if (req.curriculumLinks() != null && !req.curriculumLinks().isEmpty()) {
            Set<String> activeCourseIds = new HashSet<>(
                    jdbc.query("SELECT id FROM courses WHERE status = 'ACTIVE'", (rs, n) -> rs.getString(1)));
            Set<String> existingLinks = new HashSet<>(jdbc.query(
                    "SELECT CONCAT(curriculum_version_id, '::', course_id) FROM curriculum_courses",
                    (rs, n) -> rs.getString(1)));
            for (CurriculumLinkRow link : req.curriculumLinks()) {
                String mid = majorIdByKey.get(majorKey(link.majorCode(), link.majorName()));
                String cid = mid != null ? cohortIdByKey.get(cohortKey(mid, link.version())) : null;
                if (cid == null || link.courseId() == null || !activeCourseIds.contains(link.courseId())
                        || link.term() < 0 || link.term() > 12) {
                    skipped++;
                    continue;
                }
                if (!existingLinks.add(cid + "::" + link.courseId())) continue;
                linkInserts.add(new Object[] { UUID.randomUUID().toString(), cid, link.courseId(), link.term() });
            }
            int coursesLinked = insertIgnoringDuplicates("""
                    INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number,
                        is_mandatory, created_at, updated_at)
                    VALUES (?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, linkInserts);
            return new ImportSummary(majorsCreated, cohortsCreated, coursesLinked, skipped);
        }

        return new ImportSummary(majorsCreated, cohortsCreated, 0, skipped);
    }

    private String resolveSemester(String existingId, String rawCode,
            Map<String, String> semIdByCode, List<Object[]> semInserts) {
        if (existingId != null && !existingId.isBlank()) return existingId;
        if (rawCode == null || rawCode.isBlank()) return null;
        String code = rawCode.strip().toUpperCase(Locale.ROOT);
        String found = semIdByCode.get(code);
        if (found != null) return found;
        Matcher m = SEM_RE.matcher(code);
        if (!m.matches()) return null;
        String term = m.group(1);
        int year = Integer.parseInt(m.group(2));
        String label = "SP".equals(term) ? "Spring" : "SU".equals(term) ? "Summer" : "Fall";
        LocalDate start = "SP".equals(term) ? LocalDate.of(year, 1, 1)
                : "SU".equals(term) ? LocalDate.of(year, 5, 1) : LocalDate.of(year, 9, 1);
        LocalDate end = "SP".equals(term) ? LocalDate.of(year, 4, 30)
                : "SU".equals(term) ? LocalDate.of(year, 8, 31) : LocalDate.of(year, 12, 31);
        String id = UUID.randomUUID().toString();
        semInserts.add(new Object[] { id, code, label + " " + year, Date.valueOf(start), Date.valueOf(end) });
        semIdByCode.put(code, id);
        return id;
    }

    /**
     * Runs an insert and survives a row that another process already created.
     * A structure import re-reads the current state, so a concurrent insert (or
     * a unique index the caller did not know about) must not fail the whole
     * batch with a 500.
     *
     * @return the number of rows that were actually inserted
     */
    private int insertIgnoringDuplicates(String sql, List<Object[]> args) {
        int inserted = 0;
        for (Object[] row : args) {
            try {
                inserted += jdbc.update(sql, row);
            } catch (DuplicateKeyException ex) {
                // The row already exists; the caller counted it as skipped.
            }
        }
        return inserted;
    }
    private String normalizeMajorStatus(String status) {
        String upper = status == null || status.isBlank() ? "ACTIVE" : status.strip().toUpperCase(Locale.ROOT);
        return List.of("ACTIVE", "DRAFT", "INACTIVE").contains(upper) ? upper : "ACTIVE";
    }
    private String majorKey(String code, String name) {
        String c = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return c + "::" + n;
    }
    private String cohortKey(String majorId, String version) {
        return majorId + "::" + (version == null ? "" : version.strip().toUpperCase(Locale.ROOT));
    }

    public record MajorRow(String code, String name, Integer totalCredits, BigDecimal durationYears, String status) {}
    public record CohortRow(String majorCode, String majorName, String majorId, String version,
            String effectiveFromSemesterId, String effectiveToSemesterId,
            String startSemesterCode, String endSemesterCode, Boolean isCurrent, String status) {}
    public record CurriculumLinkRow(String majorCode, String majorName, String version, String courseId, int term) {}
    public record ImportRequest(List<MajorRow> majors, List<CohortRow> cohorts, List<CurriculumLinkRow> curriculumLinks) {}
    public record ImportSummary(int majorsCreated, int cohortsCreated, int coursesLinked, int skipped) {}
}
