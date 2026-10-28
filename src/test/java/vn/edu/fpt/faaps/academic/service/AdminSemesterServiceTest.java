package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class AdminSemesterServiceTest {
    private AdminSemesterService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:semesters-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE semesters (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                name VARCHAR(100) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL,
                is_current BOOLEAN DEFAULT FALSE, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        service = new AdminSemesterService(jdbc);
    }

    @Test
    void createsAndUpdatesSemesterForNewCohort() {
        var item = service.create(input("fa2026", "Fall 2026"));
        assertEquals("FA2026", item.code());
        assertEquals(1, service.list().size());
        assertEquals("Autumn 2026", service.update(item.id(), input("fa2026", "Autumn 2026")).name());
    }

    @Test
    void rejectsDuplicateCodeAndInvalidDates() {
        service.create(input("FA2026", "Fall 2026"));
        var duplicate = assertThrows(ResponseStatusException.class,
                () -> service.create(input("FA2026", "Another Fall")));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        var invalid = assertThrows(ResponseStatusException.class,
                () -> service.create(new AdminSemesterService.SemesterInput("SP2027", "Spring 2027",
                        LocalDate.of(2027, 5, 1), LocalDate.of(2027, 1, 1))));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
    }

    private AdminSemesterService.SemesterInput input(String code, String name) {
        return new AdminSemesterService.SemesterInput(code, name,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));
    }
}
