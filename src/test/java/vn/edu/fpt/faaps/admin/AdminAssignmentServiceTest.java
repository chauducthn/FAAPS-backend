package vn.edu.fpt.faaps.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
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
    private UUID cohortId;
    private UUID majorId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-assignments-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, full_name VARCHAR(255), email VARCHAR(255), avatar_url VARCHAR(500), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, advisor_code VARCHAR(50), max_advisees INT, status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, version VARCHAR(50), major_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50), curriculum_version_id VARCHAR(36))");
        jdbc.execute("""
                CREATE TABLE student_advisor_assignments (id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36), advisor_id VARCHAR(36), assigned_date DATE,
                    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, assigned_by_user_id VARCHAR(36), notes CLOB,
                    end_date DATE, is_primary BOOLEAN, status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE advising_requests (student_id VARCHAR(36), advisor_id VARCHAR(36), status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE cohort_advisors (cohort_id VARCHAR(36), advisor_id VARCHAR(36), is_active BOOLEAN, PRIMARY KEY (cohort_id, advisor_id))");
        jdbc.execute("CREATE TABLE advising_sessions (id VARCHAR(36), student_id VARCHAR(36), advisor_id VARCHAR(36), status VARCHAR(20), scheduled_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36), user_id VARCHAR(36), notification_type VARCHAR(20), title VARCHAR(255), message VARCHAR(500), is_read BOOLEAN, related_student_id VARCHAR(36), resolved_at TIMESTAMP)");
        firstAdvisor = addAdvisor("A", 3);
        secondAdvisor = addAdvisor("B", 3);
        majorId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors VALUES (?, 'SE')", majorId.toString());
        String curriculum = UUID.randomUUID().toString();
        cohortId = UUID.fromString(curriculum);
        jdbc.update("INSERT INTO curriculum_versions (id, version, major_id) VALUES (?, 'K17', ?)",
                curriculum, majorId.toString());
        firstStudent = addStudent("S1", curriculum);
        secondStudent = addStudent("S2", curriculum);
        jdbc.update("INSERT INTO cohort_advisors VALUES (?, ?, TRUE)", curriculum, firstAdvisor.toString());
        jdbc.update("INSERT INTO cohort_advisors VALUES (?, ?, TRUE)", curriculum, secondAdvisor.toString());
        assignments = new AdminAssignmentService(jdbc, new CohortAdvisorService(jdbc));
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
        jdbc.update("INSERT INTO advising_sessions (id, student_id, advisor_id, status, scheduled_at) VALUES (?, ?, ?, 'SCHEDULED', DATEADD('DAY', 1, CURRENT_TIMESTAMP))",
                UUID.randomUUID().toString(), assignedToFirst.toString(), firstAdvisor.toString());
        assertThrows(AdminAssignmentService.AppointmentWarningRequired.class, () ->
                transactions.execute(status -> assignments.assign(List.of(assignedToFirst), secondAdvisor)));
        assertEquals(firstAdvisor.toString(), assignments.activeAdvisor(assignedToFirst));
        assertEquals("SCHEDULED", jdbc.queryForObject(
                "SELECT status FROM advising_sessions WHERE student_id = ?", String.class, assignedToFirst.toString()));
        assertEquals(1, assignments.appointmentImpacts(List.of(assignedToFirst), secondAdvisor).size());
        assertEquals(1, transactions.execute(status ->
                assignments.assign(List.of(assignedToFirst), secondAdvisor, true)).intValue());
        assertEquals(2, assignments.history(assignedToFirst).size());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, Integer.class, assignedToFirst.toString()).intValue());
        assertEquals(secondAdvisor.toString(), jdbc.queryForObject(
                "SELECT advisor_id FROM advising_requests WHERE student_id = ?", String.class, assignedToFirst.toString()));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM advising_sessions WHERE student_id = ?", String.class, assignedToFirst.toString()));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, assignedToFirst.toString()).intValue());
    }

    @Test
    void rejectsUnknownStudentButAllowsAdvisorCapacityWarning() {
        assertThrows(IllegalArgumentException.class, () ->
                assignments.assign(List.of(firstStudent, firstStudent), firstAdvisor));
        assertThrows(AdminAssignmentService.Conflict.class, () ->
                transactions.execute(status -> assignments.assign(List.of(UUID.randomUUID()), firstAdvisor)));
        jdbc.update("UPDATE advisors SET max_advisees = 1 WHERE user_id = ?", firstAdvisor.toString());
        assertEquals(2, transactions.execute(status ->
                assignments.assign(List.of(firstStudent, secondStudent), firstAdvisor)).intValue());
        assertEquals(true, assignments.previewCohort(cohortId, List.of(firstAdvisor))
                .get("warning"));
    }

    @Test
    void autoAssignByCohortBalancesFinalLoadAndWarnsAboveLimit() {
        jdbc.update("UPDATE advisors SET max_advisees = 1 WHERE user_id = ?", firstAdvisor.toString());
        Map<String, Object> preview = assignments.previewCohort(cohortId, List.of(firstAdvisor, secondAdvisor));
        assertEquals(2, preview.get("assigned"));
        assertEquals(false, preview.get("warning"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocation = (List<Map<String, Object>>) preview.get("allocation");
        assertEquals(0L, allocation.get(0).get("totalBefore"));
        Map<String, Object> result = transactions.execute(status ->
                assignments.confirmCohort(cohortId, List.of(firstAdvisor, secondAdvisor)));
        assertEquals(2, result.get("assigned"));
        assertEquals(0, assignments.previewCohort(cohortId, List.of(firstAdvisor, secondAdvisor)).get("assigned"));
        jdbc.update("UPDATE advisors SET max_advisees = 1 WHERE user_id = ?", secondAdvisor.toString());
        addStudent("S3", cohortId.toString());
        Map<String, Object> excess = assignments.previewCohort(cohortId, List.of(firstAdvisor, secondAdvisor));
        assertEquals(true, excess.get("warning"));
        assertEquals(1, transactions.execute(status ->
                assignments.confirmCohort(cohortId, List.of(firstAdvisor, secondAdvisor))).get("assigned"));
    }

    @Test
    void autoAssignBalancesExistingUnequalAdvisorLoads() {
        transactions.execute(status -> assignments.assign(List.of(firstStudent, secondStudent), firstAdvisor));
        addStudent("S3", cohortId.toString());
        addStudent("S4", cohortId.toString());
        Map<String, Object> result = transactions.execute(status ->
                assignments.confirmCohort(cohortId, List.of(firstAdvisor, secondAdvisor)));
        assertEquals(2, result.get("assigned"));
        assertEquals(2L, assignments.advisor(firstAdvisor).advisor().activeStudents());
        assertEquals(2L, assignments.advisor(secondAdvisor).advisor().activeStudents());
    }

    @Test
    void configuredAdvisorsWorkBeforeStudentsJoinAndReadNotificationsRemainPending() {
        CohortAdvisorService pools = new CohortAdvisorService(jdbc);
        AssignmentWorkflowService workflow = new AssignmentWorkflowService(jdbc, assignments, pools);
        UUID upcomingCohort = UUID.randomUUID();
        jdbc.update("INSERT INTO curriculum_versions (id, version, major_id) VALUES (?, 'K18', ?)",
                upcomingCohort.toString(), majorId.toString());
        assertEquals(2, transactions.execute(status ->
                pools.replace(upcomingCohort, List.of(firstAdvisor, secondAdvisor))).size());
        transactions.execute(status -> { pools.include(upcomingCohort, firstAdvisor); return null; });
        assertEquals(0, pools.list(upcomingCohort).get(0).cohortStudents());
        assertEquals(2, workflow.advisorsWithCohorts().stream()
                .filter(item -> upcomingCohort.toString().equals(item.get("cohortId")))
                .filter(item -> (boolean) item.get("inPool") && (long) item.get("studentCount") == 0)
                .count());
        AssignmentWorkflowService.Page<Map<String, Object>> advisorPage =
                workflow.advisorsWithCohorts(upcomingCohort, "A", 0, 1);
        assertEquals(1, advisorPage.totalElements());
        assertEquals(1, advisorPage.items().size());

        UUID newcomer = addStudent("S3", upcomingCohort.toString());
        jdbc.update("INSERT INTO notifications (id, user_id, notification_type, title, message, is_read, related_student_id) VALUES (?, ?, 'ADVISOR_REQUEST', 'Request', 'Assign', TRUE, ?)",
                UUID.randomUUID().toString(), firstAdvisor.toString(), newcomer.toString());
        assertEquals(1L, workflow.summary().get("pendingAdvisorRequests"));
        assertEquals(true, workflow.unassigned(upcomingCohort).get(0).get("hasPendingRequest"));
        assertEquals(1, transactions.execute(status ->
                assignments.confirmCohort(upcomingCohort, null)).get("assigned"));
        assertEquals(0L, workflow.summary().get("pendingAdvisorRequests"));
    }

    @Test
    void cohortAssignmentKeepsExistingAdvisorsAndOnlyAssignsNewStudents() {
        AssignmentWorkflowService workflow = new AssignmentWorkflowService(jdbc, assignments,
                new CohortAdvisorService(jdbc));
        transactions.execute(status -> assignments.assign(List.of(firstStudent), firstAdvisor));
        jdbc.update("UPDATE advisors SET max_advisees = 1 WHERE user_id = ?", secondAdvisor.toString());
        assertEquals(1, workflow.previewCohortAssignment(cohortId, secondAdvisor).get("unassignedStudents"));
        Map<String, Object> result = transactions.execute(status -> workflow.assignCohort(cohortId, secondAdvisor));
        assertEquals(1, result.get("assigned"));
        assertEquals(firstAdvisor.toString(), assignments.activeAdvisor(firstStudent));
        assertEquals(secondAdvisor.toString(), assignments.activeAdvisor(secondStudent));
        assertEquals(0, transactions.execute(status -> workflow.assignCohort(cohortId, secondAdvisor)).get("assigned"));
        assertEquals(1L, workflow.advisorsWithCohorts().stream()
                .filter(item -> cohortId.toString().equals(item.get("cohortId")))
                .filter(item -> firstAdvisor.toString().equals(item.get("advisorId")))
                .findFirst().orElseThrow().get("studentCount"));
        AdminAssignmentService.Page<AdminAssignmentService.Student> studentPage =
                assignments.students(cohortId, null, false, 0, 1, null, null, null);
        assertEquals(2, studentPage.totalElements());
        assertEquals(1, studentPage.items().size());
        assertEquals(cohortId, studentPage.items().get(0).cohortId());
        assertEquals("SE", studentPage.items().get(0).majorCode());
    }

    @Test
    void adminCanLowerLimitWithoutBlockingExistingAssignments() {
        AssignmentWorkflowService workflow = new AssignmentWorkflowService(jdbc, assignments,
                new CohortAdvisorService(jdbc));
        transactions.execute(status -> assignments.assign(List.of(firstStudent, secondStudent), firstAdvisor));
        Map<String, Object> result = transactions.execute(status -> workflow.updateLimit(firstAdvisor, 1));
        assertEquals(1L, result.get("overLimitBy"));
        assertEquals(2L, assignments.advisor(firstAdvisor).advisor().activeStudents());
    }

    private UUID addAdvisor(String code, int capacity) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, full_name, email, avatar_url, status) VALUES (?, ?, ?, NULL, 'ACTIVE')", id.toString(), code, code + "@test.local");
        jdbc.update("INSERT INTO advisors (user_id, advisor_code, max_advisees, status) VALUES (?, ?, ?, 'ACTIVE')",
                id.toString(), code, capacity);
        return id;
    }

    private UUID addStudent(String code, String curriculum) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, full_name, email, avatar_url, status) VALUES (?, ?, ?, NULL, 'ACTIVE')", id.toString(), code, code + "@test.local");
        jdbc.update("INSERT INTO students (user_id, student_code, curriculum_version_id) VALUES (?, ?, ?)",
                id.toString(), code, curriculum);
        return id;
    }
}
