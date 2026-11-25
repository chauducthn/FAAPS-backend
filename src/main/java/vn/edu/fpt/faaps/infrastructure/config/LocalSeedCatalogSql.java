package vn.edu.fpt.faaps.infrastructure.config;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LocalSeedCatalogSql {

    private static final String DEFAULT_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JdbcTemplate jdbc;

    LocalSeedCatalogSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String ensureRole(String code, String name) {
        List<String> existing = jdbc.query("SELECT id FROM roles WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO roles (id, code, name) VALUES (?, ?, ?)", id, code, name);
        } catch (Exception ex) {
            jdbc.update("INSERT INTO roles (id, code) VALUES (?, ?)", id, code);
        }
        return id;
    }

    String ensureUser(String email, String roleId, String fullName, String phone) {
        List<String> existing = jdbc.query("SELECT id FROM users WHERE LOWER(email) = LOWER(?)",
                (rs, i) -> rs.getString("id"), email);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone, status)
                VALUES (?, ?, ?, ?, 'LOCAL', ?, ?, 'ACTIVE')
                """, id, roleId, email, DEFAULT_BCRYPT_HASH, fullName, phone);
        return id;
    }

    void ensureAdminAccount(String userId) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "admin_account") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_account WHERE singleton_id = 1", Integer.class);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO admin_account (singleton_id, user_id) VALUES (1, ?)", userId);
        }
    }

    String ensureMajor(String code, String name, String description, int totalCredits, double durationYears) {
        List<String> existing = jdbc.query("SELECT id FROM majors WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, id, code, name, description, totalCredits, BigDecimal.valueOf(durationYears));
        return id;
    }

    boolean hasCurrentSemester() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM semesters WHERE is_current = TRUE", Integer.class);
        return count != null && count > 0;
    }

    String ensureSemester(String code, String name, String startDate, String endDate, boolean isCurrent) {
        List<String> existing = jdbc.query("SELECT id FROM semesters WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, code, name, Date.valueOf(startDate), Date.valueOf(endDate), isCurrent);
        return id;
    }

    String ensureCurriculumVersion(String majorId, String version, String name, String semId, boolean isCurrent) {
        List<String> existing = jdbc.query(
                "SELECT id FROM curriculum_versions WHERE major_id = ? AND version = ?",
                (rs, i) -> rs.getString("id"), majorId, version);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO curriculum_versions
                    (id, major_id, version, name, effective_from_semester_id, is_current, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, id, majorId, version, name, semId, isCurrent);
        return id;
    }
}
