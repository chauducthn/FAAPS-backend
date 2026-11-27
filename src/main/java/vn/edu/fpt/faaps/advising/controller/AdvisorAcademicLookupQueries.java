package vn.edu.fpt.faaps.advising.controller;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;
import vn.edu.fpt.faaps.academic.service.CourseWorkloadService;
import vn.edu.fpt.faaps.advising.controller.AdvisorAcademicLookupController.LookupCourse;
import vn.edu.fpt.faaps.advising.controller.AdvisorAcademicLookupController.LookupCurriculum;
import vn.edu.fpt.faaps.advising.controller.AdvisorAcademicLookupController.LookupPrerequisite;
import vn.edu.fpt.faaps.advising.controller.AdvisorAcademicLookupController.LookupSemester;

class AdvisorAcademicLookupQueries {
    private static final Logger log = LoggerFactory.getLogger(AdvisorAcademicLookupQueries.class);
    private final JdbcTemplate jdbc;
    private final CourseWorkloadService workloads;

    AdvisorAcademicLookupQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.workloads = new CourseWorkloadService(jdbc);
    }

    List<LookupCourse> findCourses(String search, String category) {
        StringBuilder where = new StringBuilder(" WHERE c.status = 'ACTIVE'");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)");
            params.add(term);
            params.add(term);
        }

        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.strip())) {
            String cat = category.strip().toLowerCase(Locale.ROOT);
            where.append(" AND (LOWER(cat.code) = ? OR LOWER(cat.name) = ? OR LOWER(c.category_id) = ?)");
            params.add(cat);
            params.add(cat);
            params.add(cat);
        }

        List<CourseBase> bases = queryCourses(where + " ORDER BY c.code ASC", params.toArray());
        Map<String, CourseWorkload> workloadMap = workloads.evaluateByCourseIds(
                bases.stream().map(CourseBase::id).toList());
        List<LookupCourse> result = new ArrayList<>(bases.size());
        for (CourseBase base : bases) {
            result.add(toLookupCourse(base, workloadMap.get(base.id())));
        }
        return result;
    }

    LookupCourse findCourseDetail(UUID id) {
        List<CourseBase> bases = queryCourses(" WHERE c.id = ?", id.toString());
        if (bases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }
        CourseBase base = bases.get(0);
        return toLookupCourse(base, workloads.evaluateOne(base.id()));
    }

    List<LookupCurriculum> findCurriculums() {
        List<CurriculumBase> versions = queryCurriculumVersions();
        List<LookupCurriculum> list = new ArrayList<>(versions.size());
        for (CurriculumBase cv : versions) {
            List<CurriculumCourseRow> rows = jdbc.query("""
                    SELECT cc.semester_number, c.code, COALESCE(c.credits, 0) AS credits
                    FROM curriculum_courses cc
                    JOIN courses c ON c.id = cc.course_id
                    WHERE cc.curriculum_version_id = ?
                    ORDER BY cc.semester_number ASC, c.code ASC
                    """, (rs, row) -> new CurriculumCourseRow(
                    rs.getInt("semester_number"), rs.getString("code"), rs.getInt("credits")
            ), cv.id());

            Map<Integer, List<String>> semMap = new LinkedHashMap<>();
            int computedCredits = 0;
            for (CurriculumCourseRow r : rows) {
                semMap.computeIfAbsent(r.semesterNumber(), k -> new ArrayList<>()).add(r.courseCode());
                computedCredits += r.credits();
            }
            List<LookupSemester> semesters = semMap.entrySet().stream()
                    .map(e -> new LookupSemester(e.getKey(), e.getValue()))
                    .toList();
            int totalCredits = cv.majorCredits() > 0 ? cv.majorCredits() : computedCredits;

            list.add(new LookupCurriculum(
                    cv.id(), cv.majorCode(), cv.majorName(), cv.version(),
                    cv.name(), cv.description(), totalCredits, semesters));
        }
        return list;
    }

    private List<CourseBase> queryCourses(String tailSql, Object... params) {
        String cols = "c.id, c.code, c.name, c.credits, COALESCE(cat.name, cat.code, '') AS category_label, "
                + "c.description, c.objectives, c.learning_outcomes";
        String from = " FROM courses c LEFT JOIN course_categories cat ON cat.id = c.category_id";
        try {
            return jdbc.query("SELECT " + cols + ", c.material_link" + from + tailSql,
                    (rs, row) -> mapCourseBase(rs, true), params);
        } catch (Exception ex) {
            log.debug("Falling back to courses query without material_link: {}", ex.getMessage());
            return jdbc.query("SELECT " + cols + from + tailSql,
                    (rs, row) -> mapCourseBase(rs, false), params);
        }
    }

    private List<CurriculumBase> queryCurriculumVersions() {
        String tail = ", COALESCE(m.total_credits, 0) AS major_credits "
                + "FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id "
                + "WHERE cv.status = 'ACTIVE' AND m.status = 'ACTIVE' ORDER BY m.code ASC, cv.version DESC";
        String head = "SELECT cv.id, m.code AS major_code, m.name AS major_name, cv.version, cv.name, ";
        try {
            return jdbc.query(head + "cv.description" + tail, (rs, row) -> mapCurriculumBase(rs));
        } catch (Exception ex) {
            log.debug("Falling back to curriculum_versions query with major description: {}", ex.getMessage());
            return jdbc.query(head + "m.description" + tail, (rs, row) -> mapCurriculumBase(rs));
        }
    }

    private List<LookupPrerequisite> loadPrerequisites(String courseId) {
        return jdbc.query("""
                SELECT prereq.code AS course_code, prereq.name AS course_name,
                       cp.group_no, cp.minimum_score
                FROM course_prerequisites cp
                JOIN courses prereq ON prereq.id = cp.prerequisite_course_id
                WHERE cp.course_id = ?
                ORDER BY cp.group_no ASC, prereq.code ASC
                """, (rs, row) -> new LookupPrerequisite(
                rs.getString("course_code"), rs.getString("course_name"),
                rs.getInt("group_no"), rs.getBigDecimal("minimum_score")
        ), courseId);
    }

    private LookupCourse toLookupCourse(CourseBase base, CourseWorkload workload) {
        return new LookupCourse(
                base.id(), base.code(), base.name(), base.credits(), base.category(),
                base.description(), base.objectives(), base.learningOutcomes(),
                base.materialLink(), loadPrerequisites(base.id()), List.of(), workload);
    }

    private static CourseBase mapCourseBase(ResultSet rs, boolean hasMaterialLink) throws SQLException {
        return new CourseBase(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getInt("credits"),
                nonNull(rs.getString("category_label")), nonNull(rs.getString("description")),
                nonNull(rs.getString("objectives")), nonNull(rs.getString("learning_outcomes")),
                hasMaterialLink ? rs.getString("material_link") : null);
    }

    private static CurriculumBase mapCurriculumBase(ResultSet rs) throws SQLException {
        return new CurriculumBase(
                rs.getString("id"), rs.getString("major_code"), rs.getString("major_name"),
                rs.getString("version"), rs.getString("name"), rs.getString("description"),
                rs.getInt("major_credits"));
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    private record CourseBase(String id, String code, String name, int credits, String category,
            String description, String objectives, String learningOutcomes, String materialLink) {}

    private record CurriculumBase(String id, String majorCode, String majorName, String version,
            String name, String description, int majorCredits) {}

    private record CurriculumCourseRow(int semesterNumber, String courseCode, int credits) {}
}
