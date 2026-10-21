package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.authentication.BadCredentialsException;

class JdbcAuthAccountStoreRdsSmokeTest {
    @Test
    @EnabledIfSystemProperty(named = "faaps.rds.smoke", matches = "true")
    void googleAccountsWorkWithRealSchemaWithoutLeavingTestData() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("DB_URL"),
                System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                var dataSource = new SingleConnectionDataSource(connection, true);
                var jdbcTemplate = new JdbcTemplate(dataSource);
                var accountStore = new JdbcAuthAccountStore(dataSource);
                String testId = UUID.randomUUID().toString();
                String studentEmail = "faaps-smoke-" + testId + "@example.invalid";
                GoogleIdentity studentIdentity = new GoogleIdentity(testId, studentEmail, "Smoke Student");

                AuthAccount student = accountStore.resolveGoogleAccount(studentIdentity);
                assertEquals("STUDENT", student.role());
                assertEquals(student.id(), accountStore.resolveGoogleAccount(studentIdentity).id());
                assertNull(student.passwordHash());
                assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM students WHERE user_id = ?", Integer.class, student.id().toString()));

                String advisorId = UUID.randomUUID().toString();
                jdbcTemplate.update("""
                        INSERT INTO users (id, role_id, email, full_name, status, auth_provider)
                        SELECT ?, id, ?, 'Smoke Advisor', 'ACTIVE', 'LOCAL'
                        FROM roles WHERE code = 'ADVISOR'
                        """, advisorId, "faaps-advisor-smoke-" + testId + "@example.invalid");
                AuthAccount advisor = accountStore.resolveGoogleAccount(new GoogleIdentity(
                        "advisor-" + testId, "faaps-advisor-smoke-" + testId + "@example.invalid",
                        "Smoke Advisor"));
                assertEquals("ADVISOR", advisor.role());
                assertEquals(advisorId, advisor.id().toString());
                assertNotNull(jdbcTemplate.queryForObject(
                        "SELECT external_id FROM users WHERE id = ?", String.class, advisorId));

                assertThrows(BadCredentialsException.class, () -> accountStore.resolveGoogleAccount(
                        new GoogleIdentity("other-" + testId, studentEmail, "Other")));
            } finally {
                connection.rollback();
            }
        }
    }
}
