package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;

@Service
public class StudentAcademicService {
    private final JdbcTemplate jdbc;
    private final AcademicWarningScheduler warningScheduler;
    private final StudentAcademicQueryRepository queries;
    private final StudentProfileUpdater profileUpdater;
    private final StudentFlmPreferencesService flmPreferences;
    private final CourseWorkloadService workloads;
    private final CurriculumSubstitutionService substitutions;

    public StudentAcademicService(JdbcTemplate jdbc) {
        this(jdbc, new AcademicWarningScheduler(jdbc), new StudentFlmPreferencesService(jdbc),
                new CourseEquivalenceService(jdbc));
    }

    public StudentAcademicService(JdbcTemplate jdbc, AcademicWarningScheduler warningScheduler) {
        this(jdbc, warningScheduler, new StudentFlmPreferencesService(jdbc),
                new CourseEquivalenceService(jdbc));
    }

    @Autowired
    public StudentAcademicService(JdbcTemplate jdbc, AcademicWarningScheduler warningScheduler,
            StudentFlmPreferencesService flmPreferences, CourseEquivalenceService equivalences) {
        this.jdbc = jdbc;
        this.warningScheduler = warningScheduler;
        this.queries = new StudentAcademicQueryRepository(jdbc);
        this.profileUpdater = new StudentProfileUpdater(jdbc, queries);
        this.flmPreferences = flmPreferences;
        this.workloads = new CourseWorkloadService(jdbc);
        this.substitutions = new CurriculumSubstitutionService(jdbc, equivalences);
    }

    public Profile profile(UUID studentId) {
        return queries.profile(studentId);
    }

    @Transactional
    public Profile updateProfile(UUID studentId, String fullName, String phone) {
        return updateProfile(studentId, fullName, phone, null, null, null);
    }

    @Transactional
    public Profile updateProfile(UUID studentId, String fullName, String phone,
            String email, String studentCode, Integer currentSemester) {
        return profileUpdater.updateProfile(studentId, fullName, phone, email, studentCode, currentSemester);
    }

    @Transactional
    public Profile changeCurriculum(UUID studentId, UUID curriculumVersionId) {
        return profileUpdater.changeCurriculum(studentId, curriculumVersionId);
    }

    /**
     * Marks the first-login setup as done. The setup needs a major/curriculum, a
     * specialization and an imported transcript, so the request is rejected when
     * one of them is still missing.
     */
    @Transactional
    public Profile completeOnboarding(UUID studentId) {
        Profile profile = profile(studentId);
        if (profile.curriculumVersionId() == null || profile.curriculumVersionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Choose your major and curriculum before finishing the setup.");
        }
        if (!hasFlmPreferences(studentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Choose your specialization and electives before finishing the setup.");
        }
        if (queries.transcript(studentId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Import your transcript (.xlsx) before finishing the setup.");
        }
        jdbc.update("UPDATE students SET onboarding_completed_at = COALESCE(onboarding_completed_at, CURRENT_TIMESTAMP), "
                + "updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", studentId.toString());
        return profile(studentId);
    }

    /** True when the student saved at least one FLM preference (combo or elective). */
    private boolean hasFlmPreferences(UUID studentId) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM student_curriculum_preferences WHERE student_id = ? "
                            + "AND (combo_code IS NOT NULL OR english_prep IS NOT NULL "
                            + "OR musical_instrument IS NOT NULL OR physical_education IS NOT NULL)",
                    Integer.class, studentId.toString());
            return count != null && count > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public List<TranscriptRow> transcript(UUID studentId) {
        return queries.transcript(studentId);
    }

    /**
     * The student's progress. Completed requirements are counted by the same matcher
     * that marks the curriculum cards, so the two never disagree.
     */
    public Progress progress(UUID studentId) {
        Progress base = queries.progress(studentId);
        Curriculum curr = queries.curriculum(studentId);
        if (curr == null || curr.id() == null) return base;
        int completed = substitutions.passedCurriculumCourseCount(studentId, curr.id());
        return new Progress(base.requiredCredits(), base.earnedCredits(), base.curriculumCourses(),
                base.currentGpa(), completed, base.semesters());
    }

    public List<Course> courses(String search, int page, int size) {
        return queries.courses(search, page, size);
    }

    public CoursePage coursesPaged(String search, int page, int size) {
        return queries.coursesPaged(search, page, size);
    }

    public Course course(UUID id) {
        return queries.course(id);
    }

    public List<Prerequisite> prerequisites(UUID courseId) {
        return queries.prerequisites(courseId);
    }

    public Curriculum curriculum(UUID studentId) {
        return queries.curriculum(studentId);
    }

    public List<CurriculumCourse> curriculumCourses(UUID studentId) {
        List<CurriculumCourse> raw = queries.curriculumCourses(studentId);
        Curriculum curr = queries.curriculum(studentId);
        List<CurriculumCourse> resolved = flmPreferences.apply(studentId, curr != null ? curr.majorCode() : null, raw);
        if (resolved.isEmpty()) return resolved;
        Map<String, String> rawCodeById = new HashMap<>(raw.size());
        for (CurriculumCourse r : raw) {
            if (r.courseId() != null && r.code() != null) rawCodeById.put(r.courseId(), r.code().trim().toUpperCase(Locale.ROOT));
        }
        List<String> eligibleIds = resolved.stream().filter(c -> isWorkloadEligible(c, rawCodeById))
                .map(CurriculumCourse::courseId).toList();
        Map<String, CourseWorkload> map = workloads.evaluateByCourseIds(eligibleIds);
        List<CurriculumCourse> withWorkload = resolved.stream()
                .map(c -> isWorkloadEligible(c, rawCodeById) ? c.withWorkload(map.get(c.courseId())) : c)
                .toList();
        return applySubstitutions(studentId, curr != null ? curr.id() : null, withWorkload);
    }

    /**
     * Marks the curriculum courses the student fulfilled through a declared substitute.
     *
     * <p>When a course was replaced (for example the K18 curriculum lists CSI106 while
     * the student passed the older CSI104), the curriculum course keeps its own code
     * but carries the substitute code so the portal can explain how it was met.
     */
    private List<CurriculumCourse> applySubstitutions(UUID studentId, String curriculumVersionId,
            List<CurriculumCourse> courses) {
        if (curriculumVersionId == null || courses.isEmpty()) return courses;
        Map<String, String> substituteByCurriculumCourse = new HashMap<>();
        for (CurriculumSubstitutionService.SatisfiedCourse s :
                substitutions.satisfiedCurriculumCourses(studentId, curriculumVersionId)) {
            if (s.viaSubstitute() && s.fulfilledByCode() != null) {
                substituteByCurriculumCourse.put(s.curriculumCourseId(), s.fulfilledByCode());
            }
        }
        if (substituteByCurriculumCourse.isEmpty()) return courses;
        return courses.stream()
                .map(c -> {
                    String via = substituteByCurriculumCourse.get(c.courseId());
                    return via == null ? c : c.satisfiedVia(via);
                })
                .toList();
    }

    private static boolean isWorkloadEligible(CurriculumCourse c, Map<String, String> rawCodeById) {
        if (c.courseId() == null) return false;
        String rawCode = rawCodeById.get(c.courseId());
        if (rawCode == null || c.code() == null) return true;
        String code = c.code().trim().toUpperCase(Locale.ROOT);
        return rawCode.equals(code) || (isPrepCode(rawCode) && isPrepCode(code));
    }

    private static boolean isPrepCode(String u) {
        return u.startsWith("PEN") || u.startsWith("LUK") || u.startsWith("TRS");
    }

    public List<AcademicWarning> warnings(UUID studentId) {
        return queries.warnings(studentId, warningScheduler);
    }

    public record Profile(String id, String fullName, String email, String phone, String accountStatus,
            String studentCode, String majorId, String majorCode, String majorName,
            String majorGroupId, String majorGroupCode, String majorGroupName,
            String curriculumVersionId, String cohort, Integer currentSemester, BigDecimal currentGpa,
            Integer totalCreditsEarned, String academicStatus, LocalDate dateOfBirth,
            /** True once the student finished the first-login setup (major + specialization). */
            boolean onboardingCompleted) {

        /** A student without a finished setup still has to pick a major and a specialization. */
        public boolean onboardingRequired() {
            return !onboardingCompleted;
        }
    }


    public record TranscriptRow(String id, String courseCode, String courseName, int credits,
            String semesterCode, String semesterName, BigDecimal finalScore, String status,
            int attemptNumber, int creditsEarned) {
    }

    public record SemesterSummary(String code, String name, int creditsEnrolled, int creditsEarned,
            BigDecimal semesterGpa, BigDecimal cumulativeGpa, String standing) {
    }

    public record Progress(int requiredCredits, int earnedCredits, int curriculumCourses,
            BigDecimal currentGpa, int completedCourses, List<SemesterSummary> semesters) {
    }

    public record Course(String id, String code, String name, int credits, String category,
            String description, String objectives, String learningOutcomes, String materialLink,
            String knowledgeBlock, String gradingStructure, CourseWorkload workload) {
        public Course(String id, String code, String name, int credits, String category,
                String description, String objectives, String learningOutcomes, String materialLink,
                String knowledgeBlock, String gradingStructure) {
            this(id, code, name, credits, category, description, objectives, learningOutcomes,
                    materialLink, knowledgeBlock, gradingStructure, null);
        }
        public Course(String id, String code, String name, int credits, String category,
                String description, String objectives, String learningOutcomes, String materialLink) {
            this(id, code, name, credits, category, description, objectives, learningOutcomes,
                    materialLink, null, null, null);
        }
        public Course(String id, String code, String name, int credits, String category,
                String description, String objectives, String learningOutcomes) {
            this(id, code, name, credits, category, description, objectives, learningOutcomes,
                    null, null, null, null);
        }
        public Course withWorkload(CourseWorkload w) {
            return new Course(id, code, name, credits, category, description, objectives, learningOutcomes,
                    materialLink, knowledgeBlock, gradingStructure, w);
        }
    }

    public record CoursePage(List<Course> items, int page, int size, long totalElements, long totalPages) {
    }

    public record Prerequisite(String id, String courseCode, String courseName, int groupNo,
            String type, BigDecimal minimumScore) {
    }

    public record Curriculum(String id, String version, String name, String description,
            String majorCode, String majorName) {
    }

    /**
     * A course of the student's curriculum.
     *
     * @param satisfiedViaCode when the student passed a substitute course instead of
     *     this one, the code of that substitute course; {@code null} when the course
     *     was passed directly or has not been taken yet
     */
    public record CurriculumCourse(String courseId, String code, String name, int credits,
            int semesterNumber, boolean mandatory, String groupName, CourseWorkload workload,
            String satisfiedViaCode) {
        public CurriculumCourse(String courseId, String code, String name, int credits,
                int semesterNumber, boolean mandatory, String groupName) {
            this(courseId, code, name, credits, semesterNumber, mandatory, groupName, null, null);
        }

        public CurriculumCourse(String courseId, String code, String name, int credits,
                int semesterNumber, boolean mandatory, String groupName, CourseWorkload workload) {
            this(courseId, code, name, credits, semesterNumber, mandatory, groupName, workload, null);
        }

        public CurriculumCourse withWorkload(CourseWorkload w) {
            return new CurriculumCourse(courseId, code, name, credits, semesterNumber, mandatory, groupName, w,
                    satisfiedViaCode);
        }

        /** The same course, credited through the given substitute course. */
        public CurriculumCourse satisfiedVia(String substituteCode) {
            return new CurriculumCourse(courseId, code, name, credits, semesterNumber, mandatory, groupName, workload,
                    substituteCode);
        }
    }

    public record AcademicWarning(String id, String studentId, String semesterId, String semesterCode,
            String semesterName, String warningType, String severity, String description,
            String actionRequired, LocalDateTime acknowledgedAt, LocalDateTime resolvedAt,
            LocalDateTime createdAt) {
    }
}
