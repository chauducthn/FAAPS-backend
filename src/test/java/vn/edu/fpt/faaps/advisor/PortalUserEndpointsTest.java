package vn.edu.fpt.faaps.advisor;

import vn.edu.fpt.faaps.advisor.service.AdvisorStudentService;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.fpt.faaps.notification.service.NotificationService;
import vn.edu.fpt.faaps.student.service.StudentAdvisorService;

class PortalUserEndpointsTest {
    private JdbcTemplate jdbc;
    private UUID student;
    private UUID advisor;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:portal-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE roles(id VARCHAR(36),code VARCHAR(20))");
        jdbc.execute("CREATE TABLE users(id VARCHAR(36),role_id VARCHAR(36),full_name VARCHAR(100),email VARCHAR(100),phone VARCHAR(20),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE advisors(user_id VARCHAR(36),max_advisees INT,status VARCHAR(20),assignment_start_date DATE)");
        jdbc.execute("CREATE TABLE majors(id VARCHAR(36),code VARCHAR(20),name VARCHAR(100))");
        jdbc.execute("CREATE TABLE curriculum_versions(id VARCHAR(36),version VARCHAR(20))");
        jdbc.execute("CREATE TABLE semesters(id VARCHAR(36),code VARCHAR(20))");
        jdbc.execute("CREATE TABLE students(user_id VARCHAR(36),student_code VARCHAR(20),major_id VARCHAR(36),curriculum_version_id VARCHAR(36),enrollment_semester_id VARCHAR(36),current_semester INT,current_gpa DECIMAL(4,2),total_credits_earned INT,academic_status VARCHAR(20))");
        jdbc.execute("CREATE TABLE student_advisor_assignments(id VARCHAR(36),student_id VARCHAR(36),advisor_id VARCHAR(36),group_name VARCHAR(100),assigned_date DATE,assigned_at TIMESTAMP,end_date DATE,is_primary BOOLEAN,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE notifications(id VARCHAR(36),recipient_user_id VARCHAR(36),notification_type VARCHAR(20),title VARCHAR(255),message CLOB,action_url VARCHAR(500),related_student_id VARCHAR(36),resolved_at TIMESTAMP,is_read BOOLEAN,read_at TIMESTAMP,created_at TIMESTAMP)");
        student = UUID.randomUUID(); advisor = UUID.randomUUID();
        String studentRole=UUID.randomUUID().toString(), advisorRole=UUID.randomUUID().toString();
        String major=UUID.randomUUID().toString(), curriculum=UUID.randomUUID().toString(), semester=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roles VALUES (?,'STUDENT'),(?,'ADVISOR')",studentRole,advisorRole);
        jdbc.update("INSERT INTO users VALUES (?,?, 'Nguyễn Văn Lương','student@fpt.edu.vn',NULL,'ACTIVE')",student,studentRole);
        jdbc.update("INSERT INTO users VALUES (?,?, 'Nguyễn Minh Anh','advisor@fpt.edu.vn',NULL,'ACTIVE')",advisor,advisorRole);
        jdbc.update("INSERT INTO advisors VALUES (?,30,'ACTIVE',DATE '2026-10-01')",advisor);
        jdbc.update("INSERT INTO majors VALUES (?,'SE','Software Engineering')",major);
        jdbc.update("INSERT INTO curriculum_versions VALUES (?,'K17')",curriculum);
        jdbc.update("INSERT INTO semesters VALUES (?,'FA2022')",semester);
        jdbc.update("INSERT INTO students VALUES (?,'SE173012',?,?,?,5,8.20,60,'ACTIVE')",student,major,curriculum,semester);
        jdbc.update("INSERT INTO student_advisor_assignments VALUES (?,?,?,?,CURRENT_DATE,CURRENT_TIMESTAMP,NULL,TRUE,'ACTIVE')",
                UUID.randomUUID(),student,advisor,"SE K17");
    }

    @Test
    void exposesRealAdvisorAndAssignedStudent() {
        var assigned = new StudentAdvisorService(jdbc).advisor(student);
        assertEquals(advisor, assigned.id());
        assertEquals("advisor@fpt.edu.vn", assigned.advisorCode());
        var page = new AdvisorStudentService(jdbc).list(advisor, "SE173012", 0, 20);
        assertEquals(1, page.totalElements());
        assertTrue(new AdvisorStudentService(jdbc).owns(advisor, student));
    }

    @Test
    void notificationsAreRecipientScoped() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO notifications VALUES (?,?,'INFO','Title','Message',NULL,NULL,NULL,FALSE,NULL,CURRENT_TIMESTAMP)",id,student);
        NotificationService service=new NotificationService(jdbc);
        assertEquals(1, service.list(student,true,0,20).totalElements());
        assertFalse(service.read(advisor,id));
        assertTrue(service.read(student,id));
        assertEquals(0, service.list(student,true,0,20).totalElements());
    }
}
