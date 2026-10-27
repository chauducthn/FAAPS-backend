package vn.edu.fpt.faaps.advising.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.advising.repository.AdvisorProfileRepository;

class AdvisorProfileServiceTest {
    private AdvisorProfileService service;
    private UUID advisor;

    @BeforeEach
    void setup() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:advisor-profile-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE users(id VARCHAR(36),full_name VARCHAR(255),email VARCHAR(255),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE advisors(user_id VARCHAR(36),max_advisees INT,status VARCHAR(20),assignment_start_date DATE)");
        jdbc.execute("CREATE TABLE student_advisor_assignments(id VARCHAR(36),advisor_id VARCHAR(36),status VARCHAR(20),end_date DATE)");
        advisor = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?,'Nguyen Van An','advisor@fpt.edu.vn',CURRENT_TIMESTAMP)", advisor.toString());
        jdbc.update("INSERT INTO advisors VALUES (?,30,'ACTIVE',DATE '2026-01-01')", advisor.toString());
        service = new AdvisorProfileService(new AdvisorProfileRepository(jdbc));
    }

    @Test
    void updatesOnlyEditableNameAndNormalizesWhitespace() {
        var profile = service.update(advisor, "  Nguyen   Van   Binh  ");
        assertEquals("Nguyen Van Binh", profile.fullName());
        assertEquals("advisor@fpt.edu.vn", profile.email());
        assertEquals(30, profile.maxAdvisees());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> service.update(advisor, "   "));
    }

    @Test
    void rejectsUnknownAdvisor() {
        assertThrows(ResponseStatusException.class, () -> service.get(UUID.randomUUID()));
    }
}
