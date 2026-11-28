package vn.edu.fpt.faaps.ai.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.CourseEquivalenceService;
import vn.edu.fpt.faaps.academic.service.CurriculumSubstitutionService;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerCourse;

final class StudentRoadmapStore {

    private static final Logger log = LoggerFactory.getLogger(StudentRoadmapStore.class);
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JdbcTemplate jdbc;
    private volatile boolean snapshotTableReady = false;

    StudentRoadmapStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // Created up front: DDL inside the generate transaction would commit it early on MySQL.
        ensureSnapshotTable();
    }

    /** What the planner needs to know about the student, read defensively so a partial schema still works. */
    record StudentAcademicState(
            String curriculumId,
            Double currentGpa,
            int passedCredits,
            Set<String> satisfiedCourseIds,
            Set<String> inProgressCourseIds,
            Map<String, Double> failedLastScore,
            String latestRecordTerm) {}

    StudyRoadmapDto findCurrent(UUID studentId) {
        List<String[]> plans = jdbc.query("""
                SELECT id, created_at FROM study_plans
                WHERE student_id = ? AND is_current = TRUE
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> new String[] {rs.getString("id"), timestamp(rs.getTimestamp("created_at"))},
                studentId.toString());
        if (plans.isEmpty()) {
            return null;
        }
        StudyRoadmapDto snapshot = loadSnapshot(plans.get(0)[0]);
        return snapshot != null ? snapshot : buildLegacy(plans.get(0)[0], studentId, plans.get(0)[1]);
    }

    void updateExpectedGraduationIfProvided(UUID studentId, GenerateRoadmapDto input) {
        if (input == null || input.expectedGraduationSemesterId() == null
                || input.expectedGraduationSemesterId().isBlank()) {
            return;
        }
        String expSemId = input.expectedGraduationSemesterId().strip();
        try {
            List<String> exists = jdbc.query("SELECT id FROM semesters WHERE id = ?",
                    (rs, row) -> rs.getString(1), expSemId);
            if (!exists.isEmpty()) {
                jdbc.update("""
                        UPDATE students
                        SET expected_graduation_semester_id = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = ?
                        """, expSemId, studentId.toString());
            }
        } catch (Exception ex) {
            log.debug("Could not update expected graduation semester for student {}: {}", studentId, ex.getMessage());
        }
    }

    String resolveSemesterCode(String semesterId) {
        try {
            return jdbc.query("SELECT code FROM semesters WHERE id = ?", (rs, row) -> rs.getString(1), semesterId)
                    .stream().findFirst().orElse(null);
        } catch (Exception ex) {
            log.debug("Could not resolve semester code for {}: {}", semesterId, ex.getMessage());
            return null;
        }
    }

    /** The graduation term the student saved in Study Journey, or the one stored on the student record. */
    String savedGraduationTerm(UUID studentId) {
        try {
            String term = jdbc.query("""
                    SELECT expected_graduation_term FROM student_study_journey_goals WHERE student_id = ?
                    """, (rs, row) -> rs.getString(1), studentId.toString()).stream()
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (term != null) {
                return term;
            }
        } catch (Exception ex) {
            log.debug("No Study Journey goal table: {}", ex.getMessage());
        }
        try {
            return jdbc.query("""
                    SELECT sem.code FROM students s JOIN semesters sem ON sem.id = s.expected_graduation_semester_id
                    WHERE s.user_id = ?
                    """, (rs, row) -> rs.getString(1), studentId.toString()).stream().findFirst().orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    StudentAcademicState academicState(UUID studentId) {
        String id = studentId.toString();
        String curriculumId = jdbc.queryForList("SELECT curriculum_version_id FROM students WHERE user_id = ?", id)
                .stream().map(row -> row.get("curriculum_version_id")).filter(Objects::nonNull)
                .map(Object::toString).findFirst().orElse(null);
        Double gpa = null;
        try {
            BigDecimal value = jdbc.query("SELECT current_gpa FROM students WHERE user_id = ?",
                    (rs, row) -> rs.getBigDecimal(1), id).stream().filter(Objects::nonNull).findFirst().orElse(null);
            gpa = value == null ? null : value.doubleValue();
        } catch (Exception ex) {
            log.debug("No current_gpa column: {}", ex.getMessage());
        }

        Set<String> satisfied = new HashSet<>();
        Set<String> inProgress = new HashSet<>();
        Map<String, Double> failed = new HashMap<>();
        int passedCredits = 0;
        String latestTerm = null;
        try {
            List<Map<String, Object>> records = courseRecords(id);
            Set<String> passedForCredits = new HashSet<>();
            for (Map<String, Object> record : records) {
                String course = String.valueOf(record.get("course_id"));
                Object via = record.get("satisfied_via_course_id");
                String status = String.valueOf(record.get("status")).toUpperCase();
                String code = StudentRoadmapPlanner.normalizeTerm((String) record.get("code"));
                if (code != null && (latestTerm == null
                        || Objects.requireNonNullElse(StudentRoadmapPlanner.termDistance(latestTerm, code), 0) > 1)) {
                    latestTerm = code;
                }
                switch (status) {
                    case "PASSED", "EXEMPTED" -> {
                        satisfied.add(course);
                        if (via != null) {
                            satisfied.add(via.toString());
                        }
                        if ("PASSED".equals(status) && passedForCredits.add(course)) {
                            passedCredits += ((Number) record.getOrDefault("credits", 0)).intValue();
                        }
                    }
                    case "IN_PROGRESS" -> {
                        inProgress.add(course);
                        if (via != null) {
                            inProgress.add(via.toString());
                        }
                    }
                    case "FAILED" -> {
                        Object score = record.get("final_score");
                        failed.put(via != null ? via.toString() : course,
                                score instanceof Number number ? number.doubleValue() : null);
                    }
                    default -> { }
                }
            }
        } catch (Exception ex) {
            log.debug("Could not load course records for student {}: {}", studentId, ex.getMessage());
        }
        // Requirements met through another code (a replacement or a code variant) count
        // as done, matched the same way as the curriculum page and progress.
        if (curriculumId != null) {
            try {
                new CurriculumSubstitutionService(jdbc,
                        new CourseEquivalenceService(jdbc))
                        .satisfiedCurriculumCourses(studentId, curriculumId)
                        .forEach(s -> satisfied.add(s.curriculumCourseId()));
            } catch (Exception ex) {
                log.debug("Could not match curriculum for student {}: {}", studentId, ex.getMessage());
            }
        }
        failed.keySet().removeAll(satisfied);
        failed.keySet().removeAll(inProgress);
        return new StudentAcademicState(curriculumId, gpa, passedCredits, satisfied, inProgress, failed, latestTerm);
    }

    private List<Map<String, Object>> courseRecords(String studentId) {
        String sql = """
                SELECT r.course_id, %s AS satisfied_via_course_id, r.status, r.final_score, c.credits, sem.code
                FROM student_course_records r
                JOIN courses c ON c.id = r.course_id
                LEFT JOIN semesters sem ON sem.id = r.semester_id
                WHERE r.student_id = ?
                ORDER BY r.updated_at
                """;
        try {
            return jdbc.queryForList(sql.formatted("r.satisfied_via_course_id"), studentId);
        } catch (Exception ex) {
            // Older schemas: no substitution column, no updated_at.
            return jdbc.queryForList(sql.formatted("NULL").replace("ORDER BY r.updated_at", ""), studentId);
        }
    }

    /** Curriculum courses the student still has to take, with prerequisites resolved to course ids. */
    List<PlannerCourse> openCourses(StudentAcademicState state) {
        if (state.curriculumId() == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.code, c.name, c.credits, cat.name AS category_name,
                       cc.semester_number, cc.is_mandatory
                FROM curriculum_courses cc JOIN courses c ON c.id = cc.course_id
                LEFT JOIN course_categories cat ON cat.id = c.category_id
                WHERE cc.curriculum_version_id = ? ORDER BY cc.semester_number, c.code
                """, state.curriculumId());
        Map<String, List<String>> equivalents = equivalents(state.curriculumId());
        Set<String> done = new HashSet<>(state.satisfiedCourseIds());
        done.addAll(state.inProgressCourseIds());

        List<PlannerCourse> open = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String courseId = row.get("id").toString();
            if (!seen.add(courseId) || done.contains(courseId)) {
                continue;
            }
            if (equivalents.getOrDefault(courseId, List.of()).stream().anyMatch(done::contains)) {
                continue;
            }
            Double lastScore = state.failedLastScore().get(courseId);
            boolean retake = state.failedLastScore().containsKey(courseId);
            Prerequisites prerequisites = prerequisites(courseId);
            open.add(new PlannerCourse(courseId, (String) row.get("code"), (String) row.get("name"),
                    ((Number) row.getOrDefault("credits", 3)).intValue(), (String) row.get("category_name"),
                    booleanValue(row.get("is_mandatory")),
                    row.get("semester_number") == null ? 0 : ((Number) row.get("semester_number")).intValue(),
                    retake, lastScore, prerequisites.groups(), prerequisites.codes()));
        }
        return open;
    }

    /** Courses whose equivalents (a passed substitute) should also count as done. */
    private Map<String, List<String>> equivalents(String curriculumId) {
        try {
            return new CourseEquivalenceService(jdbc).equivalenceMapByCourseId(curriculumId);
        } catch (Exception ex) {
            log.debug("Course equivalences unavailable: {}", ex.getMessage());
            return Map.of();
        }
    }

    private record Prerequisites(List<List<String>> groups, List<String> codes) {}

    private Prerequisites prerequisites(String courseId) {
        List<Object[]> rows;
        try {
            rows = jdbc.query("""
                    SELECT cp.prerequisite_course_id, p.code, cp.group_no FROM course_prerequisites cp
                    JOIN courses p ON p.id = cp.prerequisite_course_id
                    WHERE cp.course_id = ? AND (cp.type IS NULL OR cp.type = 'PREREQUISITE')
                    ORDER BY cp.group_no, p.code
                    """, (rs, row) -> new Object[] {rs.getString(1), rs.getString(2), rs.getInt(3)}, courseId);
        } catch (Exception ex) {
            // Older schemas have no group_no/type: every prerequisite is required on its own.
            rows = jdbc.query("""
                    SELECT cp.prerequisite_course_id, p.code FROM course_prerequisites cp
                    JOIN courses p ON p.id = cp.prerequisite_course_id WHERE cp.course_id = ?
                    """, (rs, row) -> new Object[] {rs.getString(1), rs.getString(2), row + 1}, courseId);
        }
        Map<Integer, List<String>> groups = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>();
        for (Object[] row : rows) {
            groups.computeIfAbsent((Integer) row[2], key -> new ArrayList<>()).add((String) row[0]);
            codes.add((String) row[1]);
        }
        return new Prerequisites(new ArrayList<>(groups.values()), codes);
    }

    void save(UUID studentId, StudyRoadmapDto roadmap) {
        String planId = roadmap.id();
        String semesterId = resolveCurrentSemesterId();
        Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1 FROM study_plans
                WHERE student_id = ? AND semester_id = ?
                """, Integer.class, studentId.toString(), semesterId);
        int version = nextVersion == null ? 1 : nextVersion;
        jdbc.update("UPDATE study_plans SET is_current = FALSE WHERE student_id = ?", studentId.toString());
        jdbc.update("""
                INSERT INTO study_plans (id, student_id, semester_id, version, is_current, plan_type,
                    total_credits_planned, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 'REGULAR', ?, 'DRAFT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, planId, studentId.toString(), semesterId, version, roadmap.totalCredits());
        for (RoadmapSemesterDto semester : roadmap.semesters()) {
            for (RoadmapCourseDto course : semester.courses()) {
                jdbc.update("""
                        INSERT INTO study_plan_courses (id, study_plan_id, course_id, planned_credits, priority, notes)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), planId, course.courseId(), course.credits(),
                        course.priority(), semester.semesterCode() + ": " + course.reason());
                jdbc.update("""
                        INSERT INTO course_recommendations (id, student_id, course_id, semester_id,
                            recommendation_source, confidence_score, reasoning, priority, status)
                        VALUES (?, ?, ?, ?, 'CURRICULUM', NULL, ?, ?, 'PENDING')
                        """, UUID.randomUUID().toString(), studentId.toString(), course.courseId(),
                        semesterId, course.reason(), course.priority());
            }
        }
        saveSnapshot(planId, studentId, roadmap);
    }

    // ---- snapshot: the full plan (terms, tips, goal check) so a reload shows exactly what was generated ----------

    private void ensureSnapshotTable() {
        if (snapshotTableReady) {
            return;
        }
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS student_roadmap_snapshots (
                        study_plan_id CHAR(36) PRIMARY KEY,
                        student_id CHAR(36) NOT NULL,
                        payload LONGTEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            snapshotTableReady = true;
        } catch (Exception ex) {
            log.debug("Could not create student_roadmap_snapshots: {}", ex.getMessage());
        }
    }

    private void saveSnapshot(String planId, UUID studentId, StudyRoadmapDto roadmap) {
        ensureSnapshotTable();
        try {
            jdbc.update("INSERT INTO student_roadmap_snapshots (study_plan_id, student_id, payload) VALUES (?, ?, ?)",
                    planId, studentId.toString(), JSON.writeValueAsString(roadmap));
        } catch (Exception ex) {
            log.warn("Could not save the roadmap snapshot for plan {}: {}", planId, ex.getMessage());
        }
    }

    private StudyRoadmapDto loadSnapshot(String planId) {
        ensureSnapshotTable();
        try {
            String payload = jdbc.query("SELECT payload FROM student_roadmap_snapshots WHERE study_plan_id = ?",
                    (rs, row) -> rs.getString(1), planId).stream().findFirst().orElse(null);
            return payload == null ? null : JSON.readValue(payload, StudyRoadmapDto.class);
        } catch (Exception ex) {
            log.debug("Could not read the roadmap snapshot for plan {}: {}", planId, ex.getMessage());
            return null;
        }
    }

    private String resolveCurrentSemesterId() {
        try {
            List<String> current = jdbc.query(
                    "SELECT id FROM semesters WHERE is_current = TRUE ORDER BY start_date DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            if (!current.isEmpty()) {
                return current.get(0);
            }
        } catch (Exception ex) {
            log.debug("Current semester lookup failed, trying today's date: {}", ex.getMessage());
        }
        try {
            List<String> byDate = jdbc.query("""
                    SELECT id FROM semesters WHERE start_date <= CURRENT_DATE AND end_date >= CURRENT_DATE
                    ORDER BY start_date DESC LIMIT 1
                    """, (rs, row) -> rs.getString(1));
            if (!byDate.isEmpty()) {
                return byDate.get(0);
            }
            byDate = jdbc.query("SELECT id FROM semesters ORDER BY start_date DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            if (!byDate.isEmpty()) {
                return byDate.get(0);
            }
        } catch (Exception ex) {
            log.debug("Latest semester lookup failed, falling back to any semester: {}", ex.getMessage());
        }
        return jdbc.query("SELECT id FROM semesters LIMIT 1", (rs, row) -> rs.getString(1))
                .stream().findFirst().orElse(null);
    }

    /** Plans generated before snapshots existed: one block with every planned course. */
    private StudyRoadmapDto buildLegacy(String planId, UUID studentId, String generatedAt) {
        List<RoadmapCourseDto> courses = jdbc.query("""
                SELECT c.id, c.code, c.name, spc.planned_credits, spc.priority, spc.notes,
                       cat.name AS category_name
                FROM study_plan_courses spc JOIN courses c ON c.id = spc.course_id
                LEFT JOIN course_categories cat ON cat.id = c.category_id WHERE spc.study_plan_id = ?
                """, (rs, row) -> new RoadmapCourseDto(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("planned_credits"), rs.getString("category_name"), true,
                rs.getString("priority"), "PLANNED", rs.getString("notes"), List.of()), planId);
        int credits = courses.stream().mapToInt(RoadmapCourseDto::credits).sum();
        var semester = new RoadmapSemesterDto(1, null, "Planned courses", credits,
                "PLANNED", courses, "Generate the roadmap again to see it term by term.");
        return new StudyRoadmapDto(planId, studentId.toString(), "Active Study Roadmap",
                "Based on current academic plan and graduation guidelines.", generatedAt,
                "RULE_BASED", null, 1, credits, List.of(semester));
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : !(value instanceof Number number) || number.intValue() == 1;
    }

    private String timestamp(Timestamp value) {
        return (value == null ? LocalDateTime.now() : value.toLocalDateTime()).format(ISO_DT);
    }
}
