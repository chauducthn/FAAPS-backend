package vn.edu.fpt.faaps.advising.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

final class AdminAssignmentTestSupport {

    record Fixture(
            JdbcTemplate jdbc,
            AdminAssignmentService assignments,
            TransactionTemplate transactions,
            UUID firstAdvisor,
            UUID secondAdvisor,
            UUID firstStudent,
            UUID secondStudent,
            UUID cohortId,
            UUID majorId) {
    }

    private AdminAssignmentTestSupport() {
    }

    static Fixture createFixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-assignments-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createSchema(jdbc);
        UUID firstAdvisor = addAdvisor(jdbc, "A", 3);
        UUID secondAdvisor = addAdvisor(jdbc, "B", 3);
        UUID majorId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors VALUES (?, 'SE')", majorId.toString());
        String curriculum = UUID.randomUUID().toString();
        UUID cohortId = UUID.fromString(curriculum);
        jdbc.update("INSERT INTO curriculum_versions (id, version, major_id) VALUES (?, 'K17', ?)",
                curriculum, majorId.toString());
        UUID firstStudent = addStudent(jdbc, "S1", curriculum);
        UUID secondStudent = addStudent(jdbc, "S2", curriculum);
        jdbc.update("INSERT INTO cohort_advisors VALUES (?, ?, TRUE)", curriculum, firstAdvisor.toString());
        jdbc.update("INSERT INTO cohort_advisors VALUES (?, ?, TRUE)", curriculum, secondAdvisor.toString());
        AdminAssignmentService assignments = new AdminAssignmentService(jdbc, new CohortAdvisorService(jdbc));
        return new Fixture(jdbc, assignments, transactions, firstAdvisor, secondAdvisor,
                firstStudent, secondStudent, cohortId, majorId);
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20))");
        jdbc.execute("INSERT INTO roles VALUES ('student-role', 'STUDENT'), ('advisor-role', 'ADVISOR')");
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), "
                + "full_name VARCHAR(255), email VARCHAR(255), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, max_advisees INT, "
                + "status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, "
                + "version VARCHAR(50), major_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, "
                + "student_code VARCHAR(50), curriculum_version_id VARCHAR(36))");
        jdbc.execute("""
                CREATE TABLE student_advisor_assignments (id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36), advisor_id VARCHAR(36), assigned_date DATE,
                    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, assigned_by_user_id VARCHAR(36), notes CLOB,
                    end_date DATE, is_primary BOOLEAN, status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE advising_requests (student_id VARCHAR(36), advisor_id VARCHAR(36), "
                + "status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE cohort_advisors (cohort_id VARCHAR(36), advisor_id VARCHAR(36), "
                + "is_active BOOLEAN, PRIMARY KEY (cohort_id, advisor_id))");
        jdbc.execute("CREATE TABLE advising_sessions (id VARCHAR(36), student_id VARCHAR(36), "
                + "advisor_id VARCHAR(36), status VARCHAR(20), scheduled_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36), recipient_user_id VARCHAR(36), "
                + "notification_type VARCHAR(20), title VARCHAR(255), message VARCHAR(500), "
                + "is_read BOOLEAN, related_student_id VARCHAR(36), resolved_at TIMESTAMP)");
    }

    static UUID addAdvisor(JdbcTemplate jdbc, String code, int capacity) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, role_id, full_name, email, status) "
                + "VALUES (?, 'advisor-role', ?, ?, 'ACTIVE')", id.toString(), code, code + "@test.local");
        jdbc.update("INSERT INTO advisors (user_id, max_advisees, status) VALUES (?, ?, 'ACTIVE')",
                id.toString(), capacity);
        return id;
    }

    static UUID addStudent(JdbcTemplate jdbc, String code, String curriculum) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, role_id, full_name, email, status) "
                + "VALUES (?, 'student-role', ?, ?, 'ACTIVE')", id.toString(), code, code + "@test.local");
        jdbc.update("INSERT INTO students (user_id, student_code, curriculum_version_id) VALUES (?, ?, ?)",
                id.toString(), code, curriculum);
        return id;
    }
}
