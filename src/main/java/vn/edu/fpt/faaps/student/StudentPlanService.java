package vn.edu.fpt.faaps.student;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudentPlanService {
    private final JdbcTemplate jdbc;

    public StudentPlanService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Semester> semesters() {
        return jdbc.query("SELECT id,code,name,start_date,end_date FROM semesters ORDER BY start_date DESC",
                (rs, row) -> new Semester(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                        rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate()));
    }

    public List<CurriculumPlan> curriculumPlans(UUID studentId) {
        return jdbc.query("""
                SELECT id FROM study_plans WHERE student_id=? AND is_current=TRUE
                ORDER BY created_at DESC,id DESC
                """, (rs, row) -> UUID.fromString(rs.getString(1)), studentId.toString())
                .stream().map(id -> curriculumPlan(studentId, id)).toList();
    }

    public CurriculumPlan curriculumPlan(UUID studentId, UUID planId) {
        PlanHeader h = jdbc.query("""
                SELECT p.id,p.semester_id,sem.code,p.version,p.status,p.plan_type,p.total_credits_planned,
                       p.previous_plan_id,p.created_at
                FROM study_plans p JOIN semesters sem ON sem.id=p.semester_id
                WHERE p.id=? AND p.student_id=?
                """, (rs, row) -> new PlanHeader(rs.getString("id"), rs.getString("semester_id"),
                rs.getString("code"), rs.getInt("version"), rs.getString("status"), rs.getString("plan_type"),
                rs.getInt("total_credits_planned"), rs.getString("previous_plan_id"),
                rs.getTimestamp("created_at").toLocalDateTime()), planId.toString(), studentId.toString())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        List<PlanCourse> courses = jdbc.query("""
                SELECT c.id,c.code,c.name,pc.planned_credits,pc.priority,pc.notes
                FROM study_plan_courses pc JOIN courses c ON c.id=pc.course_id
                WHERE pc.study_plan_id=? ORDER BY c.code
                """, (rs, row) -> new PlanCourse(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("planned_credits"), rs.getString("priority"), rs.getString("notes")), planId.toString());
        return new CurriculumPlan(h, courses, validateCourses(studentId, courses.stream().map(PlanCourse::id).toList()));
    }

    public List<CurriculumPlan> curriculumVersions(UUID studentId, UUID planId) {
        CurriculumPlan selected = curriculumPlan(studentId, planId);
        return jdbc.query("""
                SELECT id FROM study_plans WHERE student_id=? AND semester_id=? ORDER BY version DESC,created_at DESC
                """, (rs, row) -> UUID.fromString(rs.getString(1)),
                studentId.toString(), selected.header().semesterId())
                .stream().map(id -> curriculumPlan(studentId, id)).toList();
    }

    @Transactional
    public CurriculumPlan saveCurriculum(UUID studentId, UUID semesterId, String planType, List<UUID> courseIds) {
        if (courseIds == null || courseIds.isEmpty() || courseIds.size() > 50 || courseIds.contains(null)
                || new HashSet<>(courseIds).size() != courseIds.size())
            throw new IllegalArgumentException("Choose 1 to 50 distinct courses");
        lockStudent(studentId);
        List<String> semester = jdbc.query("SELECT id FROM semesters WHERE id=?", (rs, row) -> rs.getString(1), semesterId.toString());
        if (semester.isEmpty()) throw new IllegalArgumentException("Semester not found");
        String type = planType == null || planType.isBlank() ? "REGULAR" : planType;
        if (!Set.of("REGULAR", "MAKEUP", "ACCELERATED", "REDUCED").contains(type))
            throw new IllegalArgumentException("Invalid plan type");
        String curriculumId = curriculumId(studentId);
        for (UUID courseId : courseIds) {
            Integer belongs = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id=? AND course_id=?
                    """, Integer.class, curriculumId, courseId.toString());
            if (belongs == null || belongs == 0) throw new IllegalArgumentException("Course is outside your curriculum: " + courseId);
        }
        List<String> previous = jdbc.query("""
                SELECT id FROM study_plans WHERE student_id=? AND semester_id=? AND is_current=TRUE
                ORDER BY version DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> rs.getString(1), studentId.toString(), semesterId.toString());
        Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version),0)+1 FROM study_plans WHERE student_id=? AND semester_id=?
                """, Integer.class, studentId.toString(), semesterId.toString());
        int credits = 0;
        for (UUID id : courseIds) credits += jdbc.queryForObject("SELECT credits FROM courses WHERE id=?", Integer.class, id.toString());
        String id = UUID.randomUUID().toString();
        if (!previous.isEmpty()) jdbc.update("UPDATE study_plans SET is_current=FALSE WHERE id=?", previous.get(0));
        jdbc.update("""
                INSERT INTO study_plans(id,student_id,semester_id,version,is_current,previous_plan_id,
                                        plan_type,total_credits_planned,status)
                VALUES (?,?,?,?,TRUE,?,?,?,'DRAFT')
                """, id, studentId.toString(), semesterId.toString(), nextVersion,
                previous.isEmpty() ? null : previous.get(0), type, credits);
        for (UUID courseId : courseIds) {
            Integer courseCredits = jdbc.queryForObject("SELECT credits FROM courses WHERE id=?", Integer.class, courseId.toString());
            jdbc.update("""
                    INSERT INTO study_plan_courses(id,study_plan_id,course_id,planned_credits,priority)
                    VALUES (?,?,?,?, 'MEDIUM')
                    """, UUID.randomUUID().toString(), id, courseId.toString(), courseCredits);
        }
        return curriculumPlan(studentId, UUID.fromString(id));
    }

    @Transactional
    public CurriculumPlan submitCurriculum(UUID studentId, UUID planId) {
        CurriculumPlan plan = curriculumPlan(studentId, planId);
        if (!plan.issues().isEmpty()) throw new IllegalArgumentException("Resolve plan issues before submitting");
        Integer current = jdbc.queryForObject("SELECT COUNT(*) FROM study_plans WHERE id=? AND is_current=TRUE",
                Integer.class, planId.toString());
        if (current == null || current == 0) throw new IllegalArgumentException("Only the current version can be submitted");
        jdbc.update("UPDATE study_plans SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP WHERE id=?",
                planId.toString());
        return curriculumPlan(studentId, planId);
    }

    public List<CoursePlan> coursePlans(UUID studentId) {
        return jdbc.query("""
                SELECT p.id FROM course_learning_plans p WHERE p.student_id=? AND p.version=(
                  SELECT MAX(next.version) FROM course_learning_plans next
                  WHERE next.student_id=p.student_id AND next.course_id=p.course_id)
                ORDER BY p.created_at DESC
                """, (rs, row) -> UUID.fromString(rs.getString(1)), studentId.toString())
                .stream().map(id -> coursePlan(studentId, id)).toList();
    }

    public CoursePlan coursePlan(UUID studentId, UUID planId) {
        CourseHeader h = jdbc.query("""
                SELECT p.id,p.course_id,c.code,c.name,p.learning_goal,p.version,p.previous_plan_id,p.created_at
                FROM course_learning_plans p JOIN courses c ON c.id=p.course_id
                WHERE p.id=? AND p.student_id=?
                """, (rs, row) -> new CourseHeader(rs.getString("id"), rs.getString("course_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("learning_goal"),
                rs.getInt("version"), rs.getString("previous_plan_id"),
                rs.getTimestamp("created_at").toLocalDateTime()), planId.toString(), studentId.toString())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course plan not found"));
        List<Task> tasks = jdbc.query("""
                SELECT id,title,description,due_date,resource_url,sort_order FROM course_learning_tasks
                WHERE plan_id=? ORDER BY sort_order
                """, (rs, row) -> new Task(rs.getString("id"), rs.getString("title"), rs.getString("description"),
                rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                rs.getString("resource_url"), rs.getInt("sort_order")), planId.toString());
        return new CoursePlan(h, tasks);
    }

    public List<CoursePlan> courseVersions(UUID studentId, UUID planId) {
        CoursePlan selected = coursePlan(studentId, planId);
        return jdbc.query("""
                SELECT id FROM course_learning_plans WHERE student_id=? AND course_id=? ORDER BY version DESC
                """, (rs, row) -> UUID.fromString(rs.getString(1)),
                studentId.toString(), selected.header().courseId())
                .stream().map(id -> coursePlan(studentId, id)).toList();
    }

    @Transactional
    public CoursePlan saveCourse(UUID studentId, UUID courseId, String goal, List<TaskInput> tasks) {
        if (courseId == null || tasks == null || tasks.isEmpty() || tasks.size() > 100 || tasks.contains(null))
            throw new IllegalArgumentException("Choose a course and 1 to 100 learning tasks");
        if (goal != null && goal.length() > 10_000)
            throw new IllegalArgumentException("Learning goal is too long");
        lockStudent(studentId);
        for (TaskInput task : tasks) {
            if (task.title() == null || task.title().isBlank() || task.title().length() > 255)
                throw new IllegalArgumentException("Each task needs a title up to 255 characters");
            if (task.description() != null && task.description().length() > 10_000)
                throw new IllegalArgumentException("Task description is too long");
            if (task.resourceUrl() != null && task.resourceUrl().length() > 1000)
                throw new IllegalArgumentException("Resource URL is too long");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM courses WHERE id=? AND status='ACTIVE'",
                Integer.class, courseId.toString());
        if (exists == null || exists == 0) throw new IllegalArgumentException("Course not found");
        Integer inCurriculum = jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id=? AND course_id=?",
                Integer.class, curriculumId(studentId), courseId.toString());
        if (inCurriculum == null || inCurriculum == 0)
            throw new IllegalArgumentException("Course is outside your curriculum");
        List<String> previous = jdbc.query("""
                SELECT id FROM course_learning_plans WHERE student_id=? AND course_id=?
                ORDER BY version DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> rs.getString(1), studentId.toString(), courseId.toString());
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version),0)+1 FROM course_learning_plans WHERE student_id=? AND course_id=?
                """, Integer.class, studentId.toString(), courseId.toString());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO course_learning_plans(id,student_id,course_id,learning_goal,version,previous_plan_id)
                VALUES (?,?,?,?,?,?)
                """, id, studentId.toString(), courseId.toString(), goal, next,
                previous.isEmpty() ? null : previous.get(0));
        for (int index = 0; index < tasks.size(); index++) {
            TaskInput task = tasks.get(index);
            jdbc.update("""
                    INSERT INTO course_learning_tasks(id,plan_id,title,description,due_date,resource_url,sort_order)
                    VALUES (?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), id, task.title().strip(), task.description(),
                    task.dueDate(), task.resourceUrl(), index);
        }
        return coursePlan(studentId, UUID.fromString(id));
    }

    private String curriculumId(UUID studentId) {
        return jdbc.query("SELECT curriculum_version_id FROM students WHERE user_id=?",
                (rs, row) -> rs.getString(1), studentId.toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Academic profile not assigned"));
    }

    private void lockStudent(UUID studentId) {
        if (jdbc.query("SELECT user_id FROM students WHERE user_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), studentId.toString()).isEmpty())
            throw new IllegalArgumentException("Your academic profile has not been assigned");
    }

    private List<String> validateCourses(UUID studentId, List<String> courseIds) {
        List<String> issues = new ArrayList<>();
        for (String id : courseIds) {
            List<Integer> groups = jdbc.query("""
                    SELECT DISTINCT group_no FROM course_prerequisites
                    WHERE course_id=? AND type='PREREQUISITE'
                    """, (rs, row) -> rs.getInt(1), id);
            for (int group : groups) {
                Integer met = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM course_prerequisites p
                        JOIN student_course_records r ON r.course_id=p.prerequisite_course_id
                        WHERE p.course_id=? AND p.group_no=? AND p.type='PREREQUISITE'
                          AND r.student_id=? AND r.status IN ('PASSED','EXEMPTED')
                          AND (p.minimum_score IS NULL OR r.final_score>=p.minimum_score)
                        """, Integer.class, id, group, studentId.toString());
                if (met == null || met == 0) issues.add("Prerequisite group " + group + " is not met for course " + id);
            }
        }
        return issues;
    }

    public record Semester(String id, String code, String name, LocalDate startDate, LocalDate endDate) {}
    public record PlanHeader(String id, String semesterId, String semesterCode, int version, String status,
            String planType, int totalCredits, String previousPlanId, LocalDateTime createdAt) {}
    public record PlanCourse(String id, String code, String name, int credits, String priority, String notes) {}
    public record CurriculumPlan(PlanHeader header, List<PlanCourse> courses, List<String> issues) {}
    public record CourseHeader(String id, String courseId, String courseCode, String courseName,
            String learningGoal, int version, String previousPlanId, LocalDateTime createdAt) {}
    public record Task(String id, String title, String description, LocalDate dueDate, String resourceUrl, int sortOrder) {}
    public record TaskInput(String title, String description, LocalDate dueDate, String resourceUrl) {}
    public record CoursePlan(CourseHeader header, List<Task> tasks) {}
}
