package vn.edu.fpt.faaps;

import vn.edu.fpt.faaps.service.AdminMajorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminMajorServiceTest {
    private JdbcTemplate jdbc;
    private AdminMajorService majors;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-majors-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE majors (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description TEXT,
                    total_credits INT NOT NULL,
                    duration_years DECIMAL(3,1) NOT NULL,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_versions (
                    id VARCHAR(36) PRIMARY KEY,
                    major_id VARCHAR(36) NOT NULL,
                    version VARCHAR(50) NOT NULL,
                    name VARCHAR(255),
                    is_current BOOLEAN DEFAULT TRUE,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE students (
                    user_id VARCHAR(36) PRIMARY KEY,
                    major_id VARCHAR(36)
                )
                """);

        majors = new AdminMajorService(jdbc);
    }

    @Test
    void createsAndListsMajorsWithCohortFilter() {
        AdminMajorService.MajorItem se = majors.create("SE", "Software Engineering", "Core SE program", 140, new BigDecimal("4.0"), "ACTIVE", "K18");
        assertNotNull(se.id());
        assertEquals("SE", se.code());

        majors.create("AI", "Artificial Intelligence", "AI program", 140, new BigDecimal("4.0"), "DRAFT", "K19");

        AdminMajorService.Page pageAll = majors.list(null, null, null, 0, 10, null);
        assertEquals(2, pageAll.totalElements());

        AdminMajorService.Page pageK18 = majors.list("K18", null, null, 0, 10, null);
        assertEquals(1, pageK18.totalElements());
        assertEquals("SE", pageK18.items().get(0).code());

        AdminMajorService.Page pageDraft = majors.list(null, "DRAFT", null, 0, 10, null);
        assertEquals(1, pageDraft.totalElements());
        assertEquals("AI", pageDraft.items().get(0).code());
    }

    @Test
    void updatesMajorAndRejectsDuplicateCode() {
        AdminMajorService.MajorItem se = majors.create("SE", "Software Engineering", "SE desc", 140, new BigDecimal("4.0"), "ACTIVE", null);
        majors.create("IA", "Information Assurance", "IA desc", 130, new BigDecimal("4.0"), "ACTIVE", null);

        majors.update(se.id(), "SE", "Software Engineering (Updated)", "Updated desc", 145, new BigDecimal("4.5"), "DRAFT");
        AdminMajorService.MajorItem updated = majors.detail(se.id());
        assertEquals("Software Engineering (Updated)", updated.name());
        assertEquals(145, updated.totalCredits());
        assertEquals("DRAFT", updated.status());

        assertThrows(AdminMajorService.Conflict.class, () ->
                majors.update(se.id(), "IA", "Duplicate", "desc", 140, new BigDecimal("4.0"), "ACTIVE"));
    }

    @Test
    void deletesMajorAndProtectsAgainstReferentialIntegrity() {
        AdminMajorService.MajorItem se = majors.create("SE", "Software Engineering", null, 140, new BigDecimal("4.0"), "ACTIVE", null);
        UUID studentUserId = UUID.randomUUID();
        jdbc.update("INSERT INTO students VALUES (?, ?)", studentUserId.toString(), se.id().toString());

        assertThrows(AdminMajorService.Conflict.class, () -> majors.delete(se.id()));

        jdbc.update("DELETE FROM students WHERE user_id = ?", studentUserId.toString());
        majors.delete(se.id());

        assertThrows(AdminMajorService.NotFound.class, () -> majors.detail(se.id()));
    }
}

