package vn.edu.fpt.faaps.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class AdminAssignmentServiceTest {
    private JdbcTemplate jdbc;
    private AdminAssignmentService assignments;
    private TransactionTemplate transactions;
    private UUID firstAdvisor;
    private UUID secondAdvisor;
    private UUID firstStudent;
    private UUID secondStudent;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-assignments-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, full_name VARCHAR(255), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, advisor_code VARCHAR(50), max_advisees INT, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, version VARCHAR(50))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50), curriculum_version_id VARCHAR(36))");
        jdbc.execute("""
                CREATE TABLE student_advisor_assignments (id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36), advisor_id VARCHAR(36), assigned_date DATE,
                    end_date DATE, is_primary BOOLEAN, status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE advising_requests (student_id VARCHAR(36), advisor_id VARCHAR(36), status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE advising_sessions (student_id VARCHAR(36), advisor_id VARCHAR(36), status VARCHAR(20), scheduled_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36), user_id VARCHAR(36), notification_type VARCHAR(20), title VARCHAR(255), message VARCHAR(500), is_read BOOLEAN)");
        firstAdvisor = addAdvisor("A", 3);
        secondAdvisor = addAdvisor("B", 3);
        String curriculum = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO curriculum_versions (id, version) VALUES (?, 'K17')", curriculum);
        firstStudent = addStudent("S1", curriculum);
        secondStudent = addStudent("S2", curriculum);
        assignments = new AdminAssignmentService(jdbc);
    }

    @Test
    void autoAssignOnlyUnassignedStudentsAndReassignmentPreservesHistory() {
        assertEquals(2, assignments.preview("K17", List.of(firstAdvisor, secondAdvisor)).unassignedStudents());
        AdminAssignmentService.Result result = transactions.execute(status ->
                assignments.confirm("K17", List.of(firstAdvisor, secondAdvisor)));
        assertEquals(2, result.assigned());
        assertEquals(1, result.allocation().get(firstAdvisor));
        assertEquals(1, result.allocation().get(secondAdvisor));
        assertEquals(0, assignments.preview("K17", List.of(firstAdvisor, secondAdvisor)).unassignedStudents());
        assertEquals(0, transactions.execute(status ->
                assignments.confirm("K17", List.of(firstAdvisor, secondAdvisor))).assigned());

        UUID assignedToFirst = assignments.students("K17", false).stream()
                .filter(student -> firstAdvisor.toString().equals(student.advisorId()))
                .findFirst().orElseThrow().id();
        jdbc.update("INSERT INTO advising_requests (student_id, advisor_id, status) VALUES (?, ?, 'PENDING')",
                assignedToFirst.toString(), firstAdvisor.toString());
        jdbc.update("INSERT INTO advising_sessions (student_id, advisor_id, status, scheduled_at) VALUES (?, ?, 'SCHEDULED', DATEADD('DAY', 1, CURRENT_TIMESTAMP))",
                assignedToFirst.toString(), firstAdvisor.toString());
        assertEquals(1, transactions.execute(status -> assignments.assign(List.of(assignedToFirst), secondAdvisor)).intValue());
        assertEquals(2, assignments.history(assignedToFirst).size());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, Integer.class, assignedToFirst.toString()).intValue());
        assertEquals(secondAdvisor.toString(), jdbc.queryForObject(
                "SELECT advisor_id FROM advising_requests WHERE student_id = ?", String.class, assignedToFirst.toString()));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM advising_sessions WHERE student_id = ?", String.class, assignedToFirst.toString()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, assignedToFirst.toString()).intValue());
    }

    @Test
    void rejectsUnknownStudentAndAdvisorCapacity() {
        assertThrows(AdminAssignmentService.Conflict.class, () ->
                transactions.execute(status -> assignments.assign(List.of(UUID.randomUUID()), firstAdvisor)));
        jdbc.update("UPDATE advisors SET max_advisees = 1 WHERE user_id = ?", firstAdvisor.toString());
        assertThrows(AdminAssignmentService.Conflict.class, () ->
                transactions.execute(status -> assignments.assign(List.of(firstStudent, secondStudent), firstAdvisor)));
    }

    private UUID addAdvisor(String code, int capacity) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, full_name, status) VALUES (?, ?, 'ACTIVE')", id.toString(), code);
        jdbc.update("INSERT INTO advisors (user_id, advisor_code, max_advisees, status) VALUES (?, ?, ?, 'ACTIVE')",
                id.toString(), code, capacity);
        return id;
    }

    private UUID addStudent(String code, String curriculum) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, full_name, status) VALUES (?, ?, 'ACTIVE')", id.toString(), code);
        jdbc.update("INSERT INTO students (user_id, student_code, curriculum_version_id) VALUES (?, ?, ?)",
                id.toString(), code, curriculum);
        return id;
    }
}
