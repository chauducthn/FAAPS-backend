package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves how a student's transcript satisfies a curriculum.
 *
 * <p>A curriculum version lists the courses a cohort must pass. The transcript of a
 * student, however, records the courses that were actually offered at the time. When a
 * course is replaced by a newer one (for example {@code CSI104} is replaced by
 * {@code CSI106} from the K18 cohort onwards), a student who passed the old course
 * must still be credited for the curriculum requirement.
 *
 * <p>This service answers that question in one place:
 * <ul>
 *   <li>{@link #satisfiedCurriculumCourses} — for every curriculum course, the student
 *       record that fulfils it (the course itself or a declared substitute), together
 *       with the semester it was taken in.</li>
 *   <li>{@link #passedCurriculumCourseCount} — how many curriculum requirements are met.</li>
 * </ul>
 *
 * <p>The catalog and the curricula are rebuilt from external exports, so the matching
 * cannot depend on hand-written rows. Declared substitutions ({@code course_prerequisites}
 * rows with {@code type = 'REPLACEMENT'}) are honoured first when they exist; after that
 * the code-level rules of {@link CourseMatchRules} apply. A replacement guessed from
 * those rules is only applied inside one student's curriculum, when it is unambiguous,
 * and it is reported as {@link MatchKind#INFERRED} so the portal can say so.
 */
@Service
public class CurriculumSubstitutionService {
    private final JdbcTemplate jdbc;
    private final CourseEquivalenceService equivalences;

    public CurriculumSubstitutionService(JdbcTemplate jdbc, CourseEquivalenceService equivalences) {
        this.jdbc = jdbc;
        this.equivalences = equivalences;
    }

    /**
     * The curriculum courses the student has fulfilled.
     *
     * <p>Every curriculum course is matched against the student's passed records in
     * four passes, strongest evidence first. A record is used for one requirement only.
     * <ol>
     *   <li>{@code DIRECT} — the student passed the curriculum course itself.</li>
     *   <li>{@code DECLARED} — an admin declared the passed course as its replacement.</li>
     *   <li>{@code EQUIVALENT} — the passed course is the same subject under another code
     *       (code variants, prep English levels, TMI electives; see
     *       {@link CourseEquivalenceService}).</li>
     *   <li>{@code INFERRED} — the passed course is not part of the curriculum, and it
     *       looks like the predecessor of exactly one requirement that is still open
     *       ({@link CourseMatchRules#isLikelyReplacement}). This is how {@code CSI104}
     *       fulfils {@code CSI106} without any hand-written data, which matters because
     *       the catalog is rebuilt from FLM exports.</li>
     * </ol>
     *
     * @param studentId the student
     * @param curriculumVersionId the curriculum the student is enrolled in
     * @return one entry per fulfilled curriculum course, in curriculum order
     */
    public List<SatisfiedCourse> satisfiedCurriculumCourses(UUID studentId, String curriculumVersionId) {
        if (curriculumVersionId == null || curriculumVersionId.isBlank()) return List.of();

        List<CurriculumRow> curriculum = jdbc.query("""
                SELECT cc.course_id, co.code, co.name, co.credits, cc.semester_number, cc.is_mandatory
                FROM curriculum_courses cc JOIN courses co ON co.id = cc.course_id
                WHERE cc.curriculum_version_id = ?
                ORDER BY cc.semester_number, co.code
                """, (rs, row) -> new CurriculumRow(rs.getString("course_id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("credits"), rs.getInt("semester_number"),
                rs.getBoolean("is_mandatory")), curriculumVersionId);
        if (curriculum.isEmpty()) return List.of();

        Map<String, List<RecordRow>> records = studentRecords(studentId);
        SatisfiedCourse[] matched = new SatisfiedCourse[curriculum.size()];
        Set<String> usedCourseIds = new HashSet<>();

        // 1. The curriculum course itself.
        for (int i = 0; i < curriculum.size(); i++) {
            CurriculumRow course = curriculum.get(i);
            RecordRow direct = bestPass(records.get(course.courseId()));
            if (direct == null) continue;
            matched[i] = satisfied(course, direct, MatchKind.DIRECT);
            usedCourseIds.add(direct.courseId());
        }

        // 2. A replacement an admin declared, then 3. the same subject under another code.
        Map<String, List<String>> declared = declaredSubstitutes(curriculumVersionId);
        matchThroughGraph(curriculum, records, matched, usedCourseIds, declared, MatchKind.DECLARED);
        Map<String, List<String>> equivalent = Map.of();
        try {
            equivalent = equivalences.equivalenceMapByCourseId(curriculumVersionId);
        } catch (RuntimeException ignored) { /* minimal test schema */ }
        matchThroughGraph(curriculum, records, matched, usedCourseIds, equivalent, MatchKind.EQUIVALENT);

        // 4. A passed course outside the curriculum that can only be the predecessor of
        //    one open requirement.
        inferReplacements(curriculum, records, matched, usedCourseIds);

        List<SatisfiedCourse> result = new ArrayList<>();
        for (SatisfiedCourse s : matched) {
            if (s != null) result.add(s);
        }
        return result;
    }

    private void matchThroughGraph(List<CurriculumRow> curriculum, Map<String, List<RecordRow>> records,
            SatisfiedCourse[] matched, Set<String> usedCourseIds, Map<String, List<String>> graph, MatchKind kind) {
        if (graph.isEmpty()) return;
        for (int i = 0; i < curriculum.size(); i++) {
            if (matched[i] != null) continue;
            CurriculumRow course = curriculum.get(i);
            RecordRow best = null;
            for (String otherId : graph.getOrDefault(course.courseId(), List.of())) {
                if (usedCourseIds.contains(otherId)) continue;
                RecordRow candidate = bestPass(records.get(otherId));
                if (candidate != null && (best == null || candidate.rank() > best.rank())) best = candidate;
            }
            if (best == null) continue;
            matched[i] = satisfied(course, best, kind);
            usedCourseIds.add(best.courseId());
        }
    }

    /**
     * Pairs passed courses that belong to no requirement with the open requirement they
     * most likely replaced.
     *
     * <p>A pair is only made when it is unambiguous: the passed course must look like the
     * predecessor of exactly one open requirement. Courses that belong to an FLM elective
     * group are left alone, since they fill an elective slot rather than replace a course.
     */
    private void inferReplacements(List<CurriculumRow> curriculum, Map<String, List<RecordRow>> records,
            SatisfiedCourse[] matched, Set<String> usedCourseIds) {
        Set<String> curriculumIds = new HashSet<>();
        for (CurriculumRow c : curriculum) curriculumIds.add(c.courseId());
        Set<String> electiveCodes = electiveGroupCodes();

        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < curriculum.size(); i++) {
            if (matched[i] == null && CourseMatchRules.parse(curriculum.get(i).code()) != null) open.add(i);
        }
        if (open.isEmpty()) return;

        Map<Integer, RecordRow> bestByRequirement = new HashMap<>();
        for (Map.Entry<String, List<RecordRow>> entry : records.entrySet()) {
            if (curriculumIds.contains(entry.getKey()) || usedCourseIds.contains(entry.getKey())) continue;
            RecordRow taken = bestPass(entry.getValue());
            if (taken == null || electiveCodes.contains(taken.courseCode().strip().toUpperCase(java.util.Locale.ROOT))) continue;
            CourseMatchRules.CourseRef takenRef = new CourseMatchRules.CourseRef(
                    taken.courseId(), taken.courseCode(), taken.courseName(), taken.credits());
            Integer target = null;
            for (int i : open) {
                CurriculumRow c = curriculum.get(i);
                if (!CourseMatchRules.isLikelyReplacement(takenRef,
                        new CourseMatchRules.CourseRef(c.courseId(), c.code(), c.name(), c.credits()))) continue;
                if (target != null) { target = null; break; }  // ambiguous: more than one requirement
                target = i;
            }
            if (target == null) continue;
            RecordRow current = bestByRequirement.get(target);
            if (current == null || taken.rank() > current.rank()) bestByRequirement.put(target, taken);
        }
        for (Map.Entry<Integer, RecordRow> entry : bestByRequirement.entrySet()) {
            matched[entry.getKey()] = satisfied(curriculum.get(entry.getKey()), entry.getValue(), MatchKind.INFERRED);
            usedCourseIds.add(entry.getValue().courseId());
        }
    }

    /** Course codes that belong to an FLM elective / combo group. */
    private Set<String> electiveGroupCodes() {
        Set<String> codes = new HashSet<>();
        try {
            jdbc.query("SELECT UPPER(course_code) FROM flm_subject_group_courses", rs -> {
                codes.add(rs.getString(1));
            });
        } catch (RuntimeException ignored) { /* minimal test schema */ }
        return codes;
    }

    private static SatisfiedCourse satisfied(CurriculumRow course, RecordRow record, MatchKind kind) {
        return new SatisfiedCourse(course.courseId(), course.code(), course.name(),
                course.credits(), course.semesterNumber(), course.mandatory(),
                record.recordId(), record.courseId(), record.courseCode(), record.courseName(),
                record.semesterCode(), record.status(), record.finalScore(), record.attemptNumber(),
                kind != MatchKind.DIRECT, kind);
    }

    /**
     * How many curriculum requirements the student has met.
     *
     * <p>Counts distinct curriculum courses that are fulfilled, whether directly or via
     * a declared substitute.
     */
    public int passedCurriculumCourseCount(UUID studentId, String curriculumVersionId) {
        return satisfiedCurriculumCourses(studentId, curriculumVersionId).size();
    }

    /**
     * The declared substitutions of a curriculum version, keyed by the curriculum course
     * id and holding the ids of the courses that replace it.
     *
     * <p>A relation scoped to another curriculum version is ignored; an unscoped
     * relation applies everywhere.
     */
    public Map<String, List<String>> declaredSubstitutes(String curriculumVersionId) {
        Map<String, List<String>> byCourse = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    SELECT cp.course_id, cp.prerequisite_course_id, cp.effective_from_curriculum_version_id
                    FROM course_prerequisites cp
                    WHERE UPPER(cp.type) = 'REPLACEMENT'
                    """, rs -> {
                String scope = rs.getString(3);
                if (scope != null && !scope.isBlank() && !scope.equals(curriculumVersionId)) return;
                byCourse.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            });
        } catch (RuntimeException ignored) { /* minimal test schema */ }
        return byCourse;
    }

    /**
     * The reverse map: the curriculum course a substitute course satisfies.
     *
     * <p>Used when a transcript row is written, so the record can remember which
     * curriculum requirement it fulfils.
     */
    public String curriculumCourseSatisfiedBy(String substituteCourseId, String curriculumVersionId) {
        if (substituteCourseId == null) return null;
        for (Map.Entry<String, List<String>> entry : declaredSubstitutes(curriculumVersionId).entrySet()) {
            if (entry.getValue().contains(substituteCourseId)) return entry.getKey();
        }
        return null;
    }

    /**
     * The student records that count as a pass, grouped by course id.
     *
     * <p>Only {@code PASSED} and {@code EXEMPTED} records fulfil a requirement. A
     * failed attempt never does, even when a later attempt passed: the later attempt is
     * the one that counts.
     */
    private Map<String, List<RecordRow>> studentRecords(UUID studentId) {
        Map<String, List<RecordRow>> byCourse = new HashMap<>();
        jdbc.query("""
                SELECT r.id, r.course_id, co.code AS course_code, co.name AS course_name, co.credits,
                       sem.code AS semester_code, r.status, r.final_score, r.attempt_number
                FROM student_course_records r
                JOIN courses co ON co.id = r.course_id
                JOIN semesters sem ON sem.id = r.semester_id
                WHERE r.student_id = ? AND r.status IN ('PASSED', 'EXEMPTED')
                """, rs -> {
            RecordRow row = new RecordRow(rs.getString("id"), rs.getString("course_id"),
                    rs.getString("course_code"), rs.getString("course_name"), rs.getString("semester_code"),
                    rs.getString("status"), rs.getBigDecimal("final_score"), rs.getInt("attempt_number"),
                    rs.getInt("credits"));
            byCourse.computeIfAbsent(row.courseId(), k -> new ArrayList<>()).add(row);
        }, studentId.toString());
        return byCourse;
    }

    /** The record with the highest status rank, breaking ties on the attempt number. */
    private RecordRow bestPass(List<RecordRow> rows) {
        if (rows == null || rows.isEmpty()) return null;
        RecordRow best = null;
        for (RecordRow row : rows) {
            if (best == null || row.rank() > best.rank()) best = row;
        }
        return best;
    }

    /**
     * The equivalence graph of a curriculum version, keyed by course code.
     *
     * <p>Only the equivalence groups that touch the curriculum are reported. A group
     * also contains the substitute courses, which are not part of the curriculum
     * themselves but are what a student may have taken instead (for example CSI104
     * for a curriculum that lists CSI106).
     */
    public Map<String, List<String>> curriculumEquivalents(String curriculumVersionId) {
        Map<String, List<String>> graph = equivalences.equivalenceMap(curriculumVersionId);
        if (graph.isEmpty()) return Map.of();
        Set<String> curriculumCodes = new HashSet<>();
        try {
            curriculumCodes.addAll(jdbc.queryForList("""
                    SELECT co.code FROM curriculum_courses cc JOIN courses co ON co.id = cc.course_id
                    WHERE cc.curriculum_version_id = ?
                    """, String.class, curriculumVersionId));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : graph.entrySet()) {
            // Keep the whole group when at least one member belongs to the curriculum,
            // so a substitute course outside the curriculum stays reachable.
            boolean touchesCurriculum = curriculumCodes.contains(entry.getKey());
            if (!touchesCurriculum) {
                touchesCurriculum = entry.getValue().stream().anyMatch(curriculumCodes::contains);
            }
            if (!touchesCurriculum) continue;
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return result;
    }

    /**
     * The equivalence graph of a curriculum, completed with how this student actually
     * fulfilled it.
     *
     * <p>The curriculum graph only knows relations between codes. A replacement inferred
     * for this student ({@code CSI104} for {@code CSI106}) is added on top, so the portal
     * maps the transcript row onto the curriculum course the same way progress does.
     */
    public Map<String, List<String>> curriculumEquivalents(UUID studentId, String curriculumVersionId) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        curriculumEquivalents(curriculumVersionId).forEach((k, v) -> result.put(k, new ArrayList<>(v)));
        for (SatisfiedCourse s : satisfiedCurriculumCourses(studentId, curriculumVersionId)) {
            if (!s.viaSubstitute() || s.fulfilledByCode() == null) continue;
            String required = s.curriculumCode().strip().toUpperCase(java.util.Locale.ROOT);
            String taken = s.fulfilledByCode().strip().toUpperCase(java.util.Locale.ROOT);
            if (required.equals(taken)) continue;
            List<String> forward = result.computeIfAbsent(required, x -> new ArrayList<>());
            if (!forward.contains(taken)) forward.add(taken);
            List<String> backward = result.computeIfAbsent(taken, x -> new ArrayList<>());
            if (!backward.contains(required)) backward.add(required);
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        result.forEach((k, v) -> frozen.put(k, Collections.unmodifiableList(v)));
        return frozen;
    }

    /** A curriculum requirement the student has met. */
    public record SatisfiedCourse(
            String curriculumCourseId,
            String curriculumCode,
            String curriculumName,
            int credits,
            int semesterNumber,
            boolean mandatory,
            String recordId,
            String fulfilledByCourseId,
            String fulfilledByCode,
            String fulfilledByName,
            String semesterCode,
            String status,
            java.math.BigDecimal finalScore,
            int attemptNumber,
            boolean viaSubstitute,
            MatchKind matchKind) {}

    /** How a curriculum requirement was fulfilled, strongest evidence first. */
    public enum MatchKind { DIRECT, DECLARED, EQUIVALENT, INFERRED }

    private record CurriculumRow(String courseId, String code, String name, int credits,
            int semesterNumber, boolean mandatory) {}

    private record RecordRow(String recordId, String courseId, String courseCode, String courseName,
            String semesterCode, String status, java.math.BigDecimal finalScore, int attemptNumber, int credits) {
        int rank() {
            int statusRank = switch (status == null ? "" : status.toUpperCase()) {
                case "EXEMPTED" -> 2;
                case "PASSED" -> 1;
                default -> 0;
            };
            return statusRank * 1000 + attemptNumber;
        }
    }
}
