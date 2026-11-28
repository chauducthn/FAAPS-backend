package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.CourseEquivalenceService;
import vn.edu.fpt.faaps.academic.service.CourseEquivalenceService.SubstitutionSuggestion;

/**
 * Admin tooling for course substitutions.
 *
 * <p>When a course is replaced by a new one, students who already passed the old
 * course must still be credited for the curriculum requirement. This controller lets an
 * admin declare that relation explicitly, scoped to one cohort or to all of them, and
 * shows which courses look like candidates.
 *
 * <p>The suggestions are advisory only: nothing is applied automatically, because a
 * wrong equivalence would corrupt the graduation audit.
 */
@RestController
@RequestMapping("/api/admin/course-substitutions")
public class AdminCourseSubstitutionController {
    private final JdbcTemplate jdbc;
    private final CourseEquivalenceService equivalences;

    public AdminCourseSubstitutionController(JdbcTemplate jdbc, CourseEquivalenceService equivalences) {
        this.jdbc = jdbc;
        this.equivalences = equivalences;
    }

    /** The declared substitutions, with the courses and curricula they affect. */
    @GetMapping
    public List<DeclaredSubstitution> list() {
        return jdbc.query("""
                SELECT cp.id, c.code AS replaced_code, c.name AS replaced_name,
                       p.code AS replacement_code, p.name AS replacement_name,
                       cv.version AS curriculum_version, cv.id AS curriculum_version_id,
                       m.code AS major_code, cp.replaced_by_reason, cp.created_at
                FROM course_prerequisites cp
                JOIN courses c ON c.id = cp.course_id
                JOIN courses p ON p.id = cp.prerequisite_course_id
                LEFT JOIN curriculum_versions cv ON cv.id = cp.effective_from_curriculum_version_id
                LEFT JOIN majors m ON m.id = cv.major_id
                WHERE UPPER(cp.type) = 'REPLACEMENT'
                ORDER BY c.code, p.code
                """, (rs, row) -> new DeclaredSubstitution(
                rs.getString("id"),
                rs.getString("replaced_code"), rs.getString("replaced_name"),
                rs.getString("replacement_code"), rs.getString("replacement_name"),
                rs.getString("curriculum_version"), rs.getString("curriculum_version_id"),
                rs.getString("major_code"),
                rs.getString("replaced_by_reason"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime()));
    }

    /**
     * The courses that look like a replacement for the given course.
     *
     * <p>Same subject prefix and same credits, ordered so the strongest candidates
     * (an identical or a near-identical course name) come first.
     */
    @GetMapping("/suggestions/{courseId}")
    public List<SubstitutionSuggestion> suggestions(@PathVariable UUID courseId) {
        List<SubstitutionSuggestion> suggestions = new ArrayList<>(equivalences.suggestSubstitutions(courseId.toString()));
        suggestions.sort((a, b) -> Integer.compare(rank(b.reason()), rank(a.reason())));
        return suggestions;
    }

    /**
     * Declares that {@code replacedCourseId} is replaced by {@code replacementCourseId}.
     *
     * @param curriculumVersionId the cohort the substitution starts at; {@code null}
     *     applies it to every curriculum version
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> declare(@Valid @RequestBody DeclareSubstitution request) {
        UUID courseId = request.replacedCourseId();
        UUID otherId = request.replacementCourseId();
        if (courseId.equals(otherId)) {
            throw new IllegalArgumentException("A course cannot replace itself");
        }
        requireCourse(courseId);
        requireCourse(otherId);
        if (request.curriculumVersionId() != null) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curriculum_versions WHERE id = ?", Integer.class,
                    request.curriculumVersionId().toString());
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("Curriculum version not found");
            }
        }
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM course_prerequisites
                WHERE course_id = ? AND prerequisite_course_id = ? AND type = 'REPLACEMENT'
                """, Integer.class, courseId.toString(), otherId.toString());
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("This substitution is already declared");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO course_prerequisites
                    (id, course_id, prerequisite_course_id, group_no, type,
                     effective_from_curriculum_version_id, replaced_by_reason, minimum_score, created_at)
                VALUES (?, ?, ?, 1, 'REPLACEMENT', ?, ?, NULL, CURRENT_TIMESTAMP)
                """, id, courseId.toString(), otherId.toString(),
                request.curriculumVersionId() == null ? null : request.curriculumVersionId().toString(),
                request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "id", id,
                "message", "Substitution declared"));
    }

    /** Removes a declared substitution. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable UUID id) {
        int deleted = jdbc.update("DELETE FROM course_prerequisites WHERE id = ? AND type = 'REPLACEMENT'",
                id.toString());
        if (deleted == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "Substitution not found"));
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "Substitution removed"));
    }

    private void requireCourse(UUID courseId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM courses WHERE id = ?", Integer.class,
                courseId.toString());
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("Course not found");
        }
    }

    private static int rank(String reason) {
        if (reason == null) return 0;
        if (reason.contains("identical course name")) return 3;
        if (reason.contains("near-identical")) return 2;
        return 1;
    }

    /** A substitution an admin has declared. */
    public record DeclaredSubstitution(
            String id,
            String replacedCourseCode, String replacedCourseName,
            String replacementCourseCode, String replacementCourseName,
            String curriculumVersion, String curriculumVersionId, String majorCode,
            String reason,
            java.time.LocalDateTime declaredAt) {}

    public record DeclareSubstitution(
            @NotNull UUID replacedCourseId,
            @NotNull UUID replacementCourseId,
            UUID curriculumVersionId,
            @Size(max = 255) String reason) {}
}
