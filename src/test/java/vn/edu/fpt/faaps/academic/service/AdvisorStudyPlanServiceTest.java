package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos.CurriculumPlan;
import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos.PlanHeader;
import vn.edu.fpt.faaps.academic.repository.AdvisorStudyPlanRepository;

class AdvisorStudyPlanServiceTest {
    private JdbcTemplate jdbc;
    private AdvisorStudyPlanService service;
    private UUID advisor;
    private UUID student;
    private UUID plan;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:advisor-plan-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE users(id VARCHAR(36),full_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE students(user_id VARCHAR(36),student_code VARCHAR(30))");
        jdbc.execute("CREATE TABLE semesters(id VARCHAR(36),code VARCHAR(30))");
        jdbc.execute("CREATE TABLE student_advisor_assignments(student_id VARCHAR(36),advisor_id VARCHAR(36),status VARCHAR(20),end_date DATE)");
        jdbc.execute("CREATE TABLE study_plans(id VARCHAR(36),student_id VARCHAR(36),semester_id VARCHAR(36),version INT,total_credits_planned INT,status VARCHAR(20),is_current BOOLEAN,submitted_at TIMESTAMP,reviewed_by_user_id VARCHAR(36),reviewed_at TIMESTAMP,review_notes CLOB,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE notifications(id VARCHAR(36),recipient_user_id VARCHAR(36),notification_type VARCHAR(30),title VARCHAR(255),message VARCHAR(500),action_url VARCHAR(255),is_read BOOLEAN)");
        advisor = UUID.randomUUID(); student = UUID.randomUUID(); plan = UUID.randomUUID();
        String semester = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users VALUES (?, 'Nguyen Van An')", student.toString());
        jdbc.update("INSERT INTO students VALUES (?, 'SE173012')", student.toString());
        jdbc.update("INSERT INTO semesters VALUES (?, 'FA2026')", semester);
        jdbc.update("INSERT INTO student_advisor_assignments VALUES (?,?,'ACTIVE',NULL)", student.toString(), advisor.toString());
        jdbc.update("INSERT INTO study_plans VALUES (?,?,?,1,15,'SUBMITTED',TRUE,CURRENT_TIMESTAMP,NULL,NULL,NULL,CURRENT_TIMESTAMP)", plan.toString(), student.toString(), semester);
        StudentPlanService planService = mock(StudentPlanService.class);
        when(planService.curriculumPlan(any(), any())).thenReturn(new CurriculumPlan(
                new PlanHeader(plan.toString(), semester, "FA2026", 1, "APPROVED", "REGULAR", 15,
                        null, null, advisor.toString(), null, "Looks good", null), List.of(), List.of()));
        service = new AdvisorStudyPlanService(new AdvisorStudyPlanRepository(jdbc), planService);
    }

    @Test
    void assignedAdvisorCanApproveAndStudentIsNotified() {
        var result = service.decide(advisor, plan, "approved", "Looks good");
        assertEquals(student.toString(), result.studentId());
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM study_plans WHERE id=?", String.class, plan.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void rejectionRequiresComment() {
        assertThrows(IllegalArgumentException.class, () -> service.decide(advisor, plan, "REJECTED", " "));
    }

    @Test
    void unassignedAdvisorCannotReadPlan() {
        assertThrows(ResponseStatusException.class,
                () -> service.detail(UUID.randomUUID(), plan));
    }

    @Test
    void planCannotBeReviewedTwice() {
        service.decide(advisor, plan, "APPROVED", null);
        assertThrows(ResponseStatusException.class,
                () -> service.decide(advisor, plan, "APPROVED", null));
    }
}
