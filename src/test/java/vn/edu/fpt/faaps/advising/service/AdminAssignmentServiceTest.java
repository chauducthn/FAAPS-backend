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
        jdbc.update("INSERT INTO advising_sessions (id, student_id, advisor_id, status, scheduled_at) "
                + "VALUES (?, ?, ?, 'SCHEDULED', DATEADD('DAY', 1, CURRENT_TIMESTAMP))",
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
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, assignedToFirst.toString()).intValue());
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
        assertEquals(true, assignments.previewCohort(cohortId, List.of(firstAdvisor)).get("warning"));
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
        addStudent(jdbc, "S3", cohortId.toString());
        Map<String, Object> excess = assignments.previewCohort(cohortId, List.of(firstAdvisor, secondAdvisor));
        assertEquals(true, excess.get("warning"));
        assertEquals(1, transactions.execute(status ->
                assignments.confirmCohort(cohortId, List.of(firstAdvisor, secondAdvisor))).get("assigned"));
    }

    @Test
    void autoAssignBalancesExistingUnequalAdvisorLoads() {
        transactions.execute(status -> assignments.assign(List.of(firstStudent, secondStudent), firstAdvisor));
        addStudent(jdbc, "S3", cohortId.toString());
        addStudent(jdbc, "S4", cohortId.toString());
        Map<String, Object> result = transactions.execute(status ->
                assignments.confirmCohort(cohortId, List.of(firstAdvisor, secondAdvisor)));
        assertEquals(2, result.get("assigned"));
        assertEquals(2L, assignments.advisor(firstAdvisor).advisor().activeStudents());
        assertEquals(2L, assignments.advisor(secondAdvisor).advisor().activeStudents());
    }

    @Test
    void autoAssignSelectedStudentsBalancesOnlyThoseProvided() {
        UUID thirdStudent = addStudent(jdbc, "SE003", cohortId.toString());
        var preview = assignments.previewCohort(cohortId, List.of(firstAdvisor, secondAdvisor), List.of(firstStudent, thirdStudent));
        assertEquals(2, preview.get("assigned"));

        Map<String, Object> result = transactions.execute(status ->
                assignments.confirmCohort(cohortId, List.of(firstAdvisor, secondAdvisor),
                        List.of(firstStudent, thirdStudent), null, "Selected batch"));
        assertEquals(2, result.get("assigned"));
        assertEquals(null, assignments.activeAdvisor(secondStudent));
        assertEquals(true, assignments.activeAdvisor(firstStudent) != null);
        assertEquals(true, assignments.activeAdvisor(thirdStudent) != null);
    }

    @Test
    void autoAssignRejectsAlreadyAssignedStudent() {
        transactions.execute(status -> assignments.assign(List.of(firstStudent), firstAdvisor));
        var ex = assertThrows(AdminAssignmentService.AssignmentError.class, () ->
                assignments.previewCohort(cohortId, List.of(firstAdvisor, secondAdvisor), List.of(firstStudent, secondStudent)));
        assertEquals("STUDENT_ALREADY_ASSIGNED", ex.code());
        var exConfirm = assertThrows(AdminAssignmentService.AssignmentError.class, () ->
                transactions.execute(status -> assignments.confirmCohort(
                        cohortId, List.of(firstAdvisor, secondAdvisor), List.of(firstStudent, secondStudent), null, "test")));
        assertEquals("STUDENT_ALREADY_ASSIGNED", exConfirm.code());
    }

    @Test
    void autoAssignRejectsStudentFromAnotherCohort() {
        UUID otherCohort = UUID.randomUUID();
        jdbc.update("INSERT INTO curriculum_versions (id, version, major_id) VALUES (?, 'K18', ?)",
                otherCohort.toString(), majorId.toString());
        UUID outsider = addStudent(jdbc, "OUTSIDE", otherCohort.toString());
        var previewError = assertThrows(AdminAssignmentService.AssignmentError.class, () ->
                assignments.previewCohort(cohortId, List.of(firstAdvisor), List.of(outsider)));
        assertEquals("STUDENT_NOT_IN_COHORT", previewError.code());
        var confirmError = assertThrows(AdminAssignmentService.AssignmentError.class, () ->
                transactions.execute(status -> assignments.confirmCohort(
                        cohortId, List.of(firstAdvisor), List.of(outsider), null, null)));
        assertEquals("STUDENT_NOT_IN_COHORT", confirmError.code());
        assertEquals(null, assignments.activeAdvisor(outsider));
    }
}
