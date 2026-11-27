package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentAcademicService {
    private final AcademicWarningScheduler warningScheduler;
    private final StudentAcademicQueryRepository queries;
    private final StudentProfileUpdater profileUpdater;
    private final StudentFlmPreferencesService flmPreferences;

    public StudentAcademicService(JdbcTemplate jdbc) {
        this(jdbc, new AcademicWarningScheduler(jdbc), new StudentFlmPreferencesService(jdbc));
    }

    public StudentAcademicService(JdbcTemplate jdbc, AcademicWarningScheduler warningScheduler) {
        this(jdbc, warningScheduler, new StudentFlmPreferencesService(jdbc));
    }

    @Autowired
    public StudentAcademicService(JdbcTemplate jdbc, AcademicWarningScheduler warningScheduler,
            StudentFlmPreferencesService flmPreferences) {
        this.warningScheduler = warningScheduler;
        this.queries = new StudentAcademicQueryRepository(jdbc);
        this.profileUpdater = new StudentProfileUpdater(jdbc, queries);
        this.flmPreferences = flmPreferences;
    }

    public Profile profile(UUID studentId) {
        return queries.profile(studentId);
    }

    @Transactional
    public Profile updateProfile(UUID studentId, String fullName, String phone) {
        return profileUpdater.updateProfile(studentId, fullName, phone);
    }

    @Transactional
    public Profile changeCurriculum(UUID studentId, UUID curriculumVersionId) {
        return profileUpdater.changeCurriculum(studentId, curriculumVersionId);
    }

    public List<TranscriptRow> transcript(UUID studentId) {
        return queries.transcript(studentId);
    }

    public Progress progress(UUID studentId) {
        return queries.progress(studentId);
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
        return flmPreferences.apply(studentId, curr != null ? curr.majorCode() : null, raw);
    }

    public List<AcademicWarning> warnings(UUID studentId) {
        return queries.warnings(studentId, warningScheduler);
    }

    public record Profile(String id, String fullName, String email, String phone, String accountStatus,
            String studentCode, String majorId, String majorCode, String majorName,
            String curriculumVersionId, String cohort, Integer currentSemester, BigDecimal currentGpa,
            Integer totalCreditsEarned, String academicStatus, LocalDate dateOfBirth) {
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
            String knowledgeBlock, String gradingStructure) {
        public Course(String id, String code, String name, int credits, String category,
                String description, String objectives, String learningOutcomes, String materialLink) {
            this(id, code, name, credits, category, description, objectives, learningOutcomes, materialLink, null, null);
        }
        public Course(String id, String code, String name, int credits, String category,
                String description, String objectives, String learningOutcomes) {
            this(id, code, name, credits, category, description, objectives, learningOutcomes, null, null, null);
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

    public record CurriculumCourse(String courseId, String code, String name, int credits,
            int semesterNumber, boolean mandatory, String groupName) {
    }

    public record AcademicWarning(String id, String studentId, String semesterId, String semesterCode,
            String semesterName, String warningType, String severity, String description,
            String actionRequired, LocalDateTime acknowledgedAt, LocalDateTime resolvedAt,
            LocalDateTime createdAt) {
    }
}
