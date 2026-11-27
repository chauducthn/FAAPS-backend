package vn.edu.fpt.faaps.advising.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService;
import vn.edu.fpt.faaps.advising.repository.AdvisorProfileRepository;

class ProfileAndOpenAdvisingTest {
    private JdbcTemplate jdbc;
    private StudentAcademicService studentAcademic;
    private AdvisorProfileService advisorProfile;
    private AdvisingConversationService conversations;

    private UUID bitGroupId;
    private UUID bbaGroupId;
    private UUID seMajorId;
    private UUID ibMajorId;
    private UUID studentBitId;
    private UUID studentBbaId;
    private UUID advisorBitId;
    private UUID advisorBbaId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:profile-open-advising-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50))");
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), email VARCHAR(255) UNIQUE, full_name VARCHAR(255), phone VARCHAR(20), date_of_birth DATE, status VARCHAR(20) DEFAULT 'ACTIVE', updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE major_groups (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE, name VARCHAR(255))");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE, name VARCHAR(255), major_group_id VARCHAR(36), status VARCHAR(20) DEFAULT 'ACTIVE')");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36), version VARCHAR(50), name VARCHAR(255), status VARCHAR(20) DEFAULT 'ACTIVE')");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50) UNIQUE, major_id VARCHAR(36), curriculum_version_id VARCHAR(36), current_semester INT DEFAULT 1, current_gpa DECIMAL(4,2) DEFAULT 0, total_credits_earned INT DEFAULT 0, academic_status VARCHAR(20) DEFAULT 'ACTIVE', updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, advisor_code VARCHAR(50) UNIQUE, major_group_id VARCHAR(36), specialization TEXT, max_advisees INT DEFAULT 30, status VARCHAR(20) DEFAULT 'ACTIVE', assignment_start_date DATE, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE student_advisor_assignments (id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36), advisor_id VARCHAR(36), status VARCHAR(20), end_date DATE)");
        jdbc.execute("CREATE TABLE advising_requests (id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36), advisor_id VARCHAR(36), study_plan_id VARCHAR(36), request_type VARCHAR(30), title VARCHAR(255), description TEXT, status VARCHAR(20), resolution_summary TEXT, resolved_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE advising_messages (id VARCHAR(36) PRIMARY KEY, advising_request_id VARCHAR(36), sender_user_id VARCHAR(36), content TEXT, is_internal BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE advising_sessions (id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36), advisor_id VARCHAR(36), advising_request_id VARCHAR(36), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36) PRIMARY KEY, recipient_user_id VARCHAR(36), notification_type VARCHAR(20), title VARCHAR(255), message TEXT, is_read BOOLEAN DEFAULT FALSE)");

        UUID studentRole = UUID.randomUUID();
        UUID advisorRole = UUID.randomUUID();
        jdbc.update("INSERT INTO roles VALUES (?, 'STUDENT')", studentRole.toString());
        jdbc.update("INSERT INTO roles VALUES (?, 'ADVISOR')", advisorRole.toString());

        bitGroupId = UUID.randomUUID();
        bbaGroupId = UUID.randomUUID();
        jdbc.update("INSERT INTO major_groups VALUES (?, 'BIT', 'Information Technology')", bitGroupId.toString());
        jdbc.update("INSERT INTO major_groups VALUES (?, 'BBA', 'Business Administration')", bbaGroupId.toString());

        seMajorId = UUID.randomUUID();
        ibMajorId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors VALUES (?, 'SE', 'Software Engineering', ?, 'ACTIVE')", seMajorId.toString(), bitGroupId.toString());
        jdbc.update("INSERT INTO majors VALUES (?, 'IB', 'International Business', ?, 'ACTIVE')", ibMajorId.toString(), bbaGroupId.toString());

        studentBitId = UUID.randomUUID();
        studentBbaId = UUID.randomUUID();
        advisorBitId = UUID.randomUUID();
        advisorBbaId = UUID.randomUUID();

        jdbc.update("INSERT INTO users(id,role_id,email,full_name,phone,status) VALUES (?,?,'se@fpt.edu.vn','SE Student','0912345601','ACTIVE')",
                studentBitId.toString(), studentRole.toString());
        jdbc.update("INSERT INTO users(id,role_id,email,full_name,phone,status) VALUES (?,?,'ib@fpt.edu.vn','IB Student','0912345602','ACTIVE')",
                studentBbaId.toString(), studentRole.toString());
        jdbc.update("INSERT INTO users(id,role_id,email,full_name,phone,status) VALUES (?,?,'adv.bit@fpt.edu.vn','BIT Advisor','0901234501','ACTIVE')",
                advisorBitId.toString(), advisorRole.toString());
        jdbc.update("INSERT INTO users(id,role_id,email,full_name,phone,status) VALUES (?,?,'adv.bba@fpt.edu.vn','BBA Advisor','0901234502','ACTIVE')",
                advisorBbaId.toString(), advisorRole.toString());

        jdbc.update("INSERT INTO students(user_id,student_code,major_id,current_semester) VALUES (?,'SE173012',?,5)",
                studentBitId.toString(), seMajorId.toString());
        jdbc.update("INSERT INTO students(user_id,student_code,major_id,current_semester) VALUES (?,'SS180001',?,3)",
                studentBbaId.toString(), ibMajorId.toString());
        jdbc.update("INSERT INTO advisors(user_id,advisor_code,major_group_id,specialization) VALUES (?,'ADV001',?,'SE')",
                advisorBitId.toString(), bitGroupId.toString());
        jdbc.update("INSERT INTO advisors(user_id,advisor_code,major_group_id,specialization) VALUES (?,'ADV002',?,'IB')",
                advisorBbaId.toString(), bbaGroupId.toString());

        studentAcademic = new StudentAcademicService(jdbc);
        advisorProfile = new AdvisorProfileService(new AdvisorProfileRepository(jdbc));
        conversations = new AdvisingConversationService(jdbc);
    }

    @Test
    void studentProfileUpdatesEmailStudentCodeSemesterAndReturnsMajorGroup() {
        var updated = studentAcademic.updateProfile(studentBitId, "Nguyen Van Luong", "0912345699",
                "luong@fpt.edu.vn", "se173099", 9);
        assertEquals("luong@fpt.edu.vn", updated.email());
        assertEquals("SE173099", updated.studentCode());
        assertEquals(9, updated.currentSemester());
        assertEquals("BIT", updated.majorGroupCode());
        assertEquals("Information Technology", updated.majorGroupName());

        ResponseStatusException dupEmail = assertThrows(ResponseStatusException.class,
                () -> studentAcademic.updateProfile(studentBitId, "Nguyen Van Luong", "0912345699",
                        "ib@fpt.edu.vn", null, null));
        assertEquals(HttpStatus.CONFLICT, dupEmail.getStatusCode());

        ResponseStatusException dupCode = assertThrows(ResponseStatusException.class,
                () -> studentAcademic.updateProfile(studentBitId, "Nguyen Van Luong", "0912345699",
                        null, "SS180001", null));
        assertEquals(HttpStatus.CONFLICT, dupCode.getStatusCode());
    }

    @Test
    void advisorProfileUpdatesEmailAndMajorGroupAndRejectsDuplicateEmail() {
        var updated = advisorProfile.update(advisorBitId, "Nguyen Thi Ha", "0901234599", "AI & SE",
                "ha.new@fpt.edu.vn", bbaGroupId);
        assertEquals("ha.new@fpt.edu.vn", updated.email());
        assertEquals("BBA", updated.majorGroupCode());

        ResponseStatusException dupEmail = assertThrows(ResponseStatusException.class,
                () -> advisorProfile.update(advisorBitId, "Nguyen Thi Ha", "0901234599", "AI & SE",
                        "adv.bba@fpt.edu.vn", null));
        assertEquals(HttpStatus.CONFLICT, dupEmail.getStatusCode());
    }

    @Test
    void openPoolAdvisingPrioritizesSameMajorGroupSupportsMultiAdvisorAndScopeMine() {
        UUID reqBit = UUID.randomUUID();
        UUID reqBba = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO advising_requests(id,student_id,advisor_id,request_type,title,description,status,created_at,updated_at)
                VALUES (?,?,NULL,'COURSE_SELECTION','BIT Q','desc','PENDING',TIMESTAMP '2026-11-27 08:00:00',TIMESTAMP '2026-11-27 08:00:00')
                """, reqBit.toString(), studentBitId.toString());
        jdbc.update("""
                INSERT INTO advising_requests(id,student_id,advisor_id,request_type,title,description,status,created_at,updated_at)
                VALUES (?,?,NULL,'COURSE_SELECTION','BBA Q','desc','PENDING',TIMESTAMP '2026-11-27 09:00:00',TIMESTAMP '2026-11-27 09:00:00')
                """, reqBba.toString(), studentBbaId.toString());

        // BIT advisor sees both, with BIT student prioritized first despite earlier timestamp
        List<AdvisingConversationService.Request> allForBit = conversations.list(advisorBitId, true, null, null, "ALL");
        assertEquals(2, allForBit.size());
        assertEquals(reqBit.toString(), allForBit.get(0).id());
        assertTrue(allForBit.get(0).sameMajorGroup());
        assertFalse(allForBit.get(1).sameMajorGroup());

        // First reply sets advisor_id; second advisor can still reply without 409 lock
        var afterFirstReply = conversations.addMessage(advisorBitId, true, reqBit, "First reply", false);
        assertEquals(advisorBitId.toString(), afterFirstReply.request().advisorId());
        assertEquals("IN_PROGRESS", afterFirstReply.request().status());

        var afterSecondReply = conversations.addMessage(advisorBbaId, true, reqBit, "Second advisor chime-in", false);
        assertEquals(advisorBitId.toString(), afterSecondReply.request().advisorId());
        assertEquals(2, afterSecondReply.messages().size());

        // scope=MINE shows participated requests for both advisors
        assertEquals(1, conversations.list(advisorBitId, true, null, null, "MINE").size());
        assertEquals(1, conversations.list(advisorBbaId, true, null, null, "MINE").size());

        // CANCELLED does not require resolutionSummary; REJECTED does
        assertThrows(IllegalArgumentException.class,
                () -> conversations.changeStatus(advisorBitId, true, reqBit, "REJECTED", null));
        var cancelled = conversations.changeStatus(advisorBbaId, true, reqBba, "CANCELLED", null);
        assertEquals("CANCELLED", cancelled.request().status());
        var rejected = conversations.changeStatus(advisorBitId, true, reqBit, "REJECTED", "Out of scope");
        assertEquals("REJECTED", rejected.request().status());
        assertEquals("Out of scope", rejected.request().resolutionSummary());
    }
}
