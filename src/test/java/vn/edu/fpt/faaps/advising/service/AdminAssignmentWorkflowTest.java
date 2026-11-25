package vn.edu.fpt.faaps.advising.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.edu.fpt.faaps.advising.service.AdminAssignmentTestSupport.addStudent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class AdminAssignmentWorkflowTest {
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
        AdminAssignmentTestSupport.Fixture f = AdminAssignmentTestSupport.createFixture();
        jdbc = f.jdbc();
        assignments = f.assignments();
        transactions = f.transactions();
        firstAdvisor = f.firstAdvisor();
        secondAdvisor = f.secondAdvisor();
        firstStudent = f.firstStudent();
        secondStudent = f.secondStudent();
        cohortId = f.cohortId();
        majorId = f.majorId();
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
        transactions.execute(status -> {
            pools.include(upcomingCohort, firstAdvisor);
            return null;
        });
        assertEquals(0, pools.list(upcomingCohort).get(0).cohortStudents());
        assertEquals(2, workflow.advisorsWithCohorts().stream()
                .filter(item -> upcomingCohort.toString().equals(item.get("cohortId")))
                .filter(item -> (boolean) item.get("inPool") && (long) item.get("studentCount") == 0)
                .count());
        AssignmentWorkflowService.Page<Map<String, Object>> advisorPage =
                workflow.advisorsWithCohorts(upcomingCohort, "A@", 0, 1);
        assertEquals(1, advisorPage.totalElements());
        assertEquals(1, advisorPage.items().size());

        UUID newcomer = addStudent(jdbc, "S3", upcomingCohort.toString());
        jdbc.update("INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, "
                + "is_read, related_student_id) VALUES (?, ?, 'ADVISOR_REQUEST', 'Request', 'Assign', TRUE, ?)",
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

    @Test
    void unassignRequiresAppointmentAcknowledgementAndMakesStudentAssignableAgain() {
        transactions.execute(status -> assignments.assign(List.of(firstStudent), firstAdvisor));
        jdbc.update("INSERT INTO advising_requests (student_id, advisor_id, status) VALUES (?, ?, 'PENDING')",
                firstStudent.toString(), firstAdvisor.toString());
        jdbc.update("INSERT INTO advising_sessions (id, student_id, advisor_id, status, scheduled_at) "
                + "VALUES (?, ?, ?, 'SCHEDULED', DATEADD('DAY', 1, CURRENT_TIMESTAMP))",
                UUID.randomUUID().toString(), firstStudent.toString(), firstAdvisor.toString());
        assertEquals(1, assignments.appointmentImpacts(List.of(firstStudent), null).size());
        assertThrows(AdminAssignmentService.AppointmentWarningRequired.class, () ->
                transactions.execute(status -> assignments.unassign(List.of(firstStudent), false, null, "Schedule changed")));
        assertEquals(firstAdvisor.toString(), assignments.activeAdvisor(firstStudent));
        assertEquals(1, transactions.execute(status ->
                assignments.unassign(List.of(firstStudent), true, null, "Schedule changed")).intValue());
        assertEquals(null, assignments.activeAdvisor(firstStudent));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM advising_requests WHERE student_id=?",
                String.class, firstStudent.toString()));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM advising_sessions WHERE student_id=?",
                String.class, firstStudent.toString()));
        assertEquals(2, assignments.previewCohort(cohortId, List.of(firstAdvisor)).get("assigned"));
        assertEquals(1, transactions.execute(status -> assignments.assign(List.of(firstStudent), secondAdvisor)).intValue());
        assertEquals(2, assignments.history(firstStudent).size());
    }

    @Test
    void summaryCountsAccountsWithoutAcademicProfileSeparately() {
        UUID missingProfile = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, role_id, full_name, email, status) "
                + "VALUES (?, 'student-role', 'New student', 'new@test.local', 'ACTIVE')", missingProfile.toString());
        AssignmentWorkflowService workflow = new AssignmentWorkflowService(jdbc, assignments,
                new CohortAdvisorService(jdbc));
        assertEquals(3L, workflow.summary().get("total"));
        assertEquals(1L, workflow.summary().get("missingAcademicProfile"));
        assertEquals(2L, workflow.summary().get("unassigned"));
    }
}
