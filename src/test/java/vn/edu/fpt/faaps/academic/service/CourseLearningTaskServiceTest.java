package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos.CourseHeader;
import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos.CoursePlan;
import vn.edu.fpt.faaps.academic.repository.CourseLearningTaskRepository;

class CourseLearningTaskServiceTest {
    private JdbcTemplate jdbc;
    private CourseLearningTaskService service;
    private UUID student;
    private UUID plan;
    private UUID task;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:task-progress-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE course_learning_plans(id VARCHAR(36),student_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE course_learning_tasks(id VARCHAR(36),plan_id VARCHAR(36),completed BOOLEAN,completed_at TIMESTAMP)");
        student = UUID.randomUUID(); plan = UUID.randomUUID(); task = UUID.randomUUID();
        jdbc.update("INSERT INTO course_learning_plans VALUES (?,?)", plan.toString(), student.toString());
        jdbc.update("INSERT INTO course_learning_tasks VALUES (?,?,FALSE,NULL)", task.toString(), plan.toString());
        CourseLearningPlanService plans = mock(CourseLearningPlanService.class);
        when(plans.get(any(), any())).thenReturn(new CoursePlan(
                new CourseHeader(plan.toString(), "course", "PRF192", "Programming", null, 1, null, null), List.of()));
        service = new CourseLearningTaskService(new CourseLearningTaskRepository(jdbc), plans);
    }

    @Test
    void studentCanCompleteAndReopenOwnTask() {
        service.update(student, plan, task, true);
        assertTrue(jdbc.queryForObject("SELECT completed FROM course_learning_tasks", Boolean.class));
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM course_learning_tasks", java.sql.Timestamp.class));
        service.update(student, plan, task, false);
        assertFalse(jdbc.queryForObject("SELECT completed FROM course_learning_tasks", Boolean.class));
    }

    @Test
    void studentCannotChangeAnotherStudentsTask() {
        assertThrows(ResponseStatusException.class,
                () -> service.update(UUID.randomUUID(), plan, task, true));
    }

    @Test
    void mismatchedPlanAndTaskReturnsNotFound() {
        assertThrows(ResponseStatusException.class,
                () -> service.update(student, UUID.randomUUID(), task, true));
    }
}
