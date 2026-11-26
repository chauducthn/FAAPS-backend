package vn.edu.fpt.faaps.dashboard.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDatabaseService {
    private static final Set<String> PRESERVED_SYSTEM_TABLES = Set.of("roles", "course_categories");
    private static final Set<String> ADMIN_SCOPED_TABLES = Set.of("users", "admin_account", "refresh_tokens");

    private final JdbcTemplate jdbc;

    public AdminDatabaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DatabaseOverview getOverview(UUID currentAdminId) {
        long users = count("users");
        long students = count("students");
        long advisors = count("advisors");
        long majors = count("majors");
        long cohorts = count("curriculum_versions");
        long courses = count("courses");
        long semesters = count("semesters");
        List<String> tables = listBaseTables();
        String adminEmail = jdbc.query(
                "SELECT email FROM users WHERE id = ?",
                (rs, n) -> rs.getString("email"),
                currentAdminId.toString()).stream().findFirst().orElse("admin");
        return new DatabaseOverview(
                tables.size(), users, students, advisors, majors, cohorts, courses, semesters, adminEmail);
    }

    @Transactional
    public ResetResult resetDatabase(UUID keepAdminId) {
        String adminId = keepAdminId.toString();
        String adminEmail = jdbc.query(
                "SELECT email FROM users WHERE id = ?",
                (rs, n) -> rs.getString("email"),
                adminId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Current admin account not found"));

        List<String> tables = listBaseTables();
        int clearedTables = 0;
        long deletedRows = 0;

        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                String lower = table.toLowerCase();
                if (PRESERVED_SYSTEM_TABLES.contains(lower)) {
                    continue;
                }
                if ("users".equals(lower)) {
                    deletedRows += jdbc.update("DELETE FROM `users` WHERE id <> ?", adminId);
                    clearedTables++;
                } else if ("admin_account".equals(lower)) {
                    deletedRows += jdbc.update("DELETE FROM `admin_account` WHERE user_id <> ?", adminId);
                    clearedTables++;
                } else if ("refresh_tokens".equals(lower)) {
                    deletedRows += jdbc.update("DELETE FROM `refresh_tokens` WHERE user_id <> ?", adminId);
                    clearedTables++;
                } else if (!ADMIN_SCOPED_TABLES.contains(lower) && table.matches("^[A-Za-z0-9_]+$")) {
                    deletedRows += jdbc.update("DELETE FROM `" + table + "`");
                    clearedTables++;
                }
            }
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        return new ResetResult(clearedTables, deletedRows, adminEmail);
    }

    private List<String> listBaseTables() {
        return jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME",
                String.class);
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
        return value != null ? value : 0L;
    }

    public record DatabaseOverview(
            int totalTables,
            long users,
            long students,
            long advisors,
            long majors,
            long cohorts,
            long courses,
            long semesters,
            String preservedAdminEmail) {
    }

    public record ResetResult(int clearedTables, long deletedRows, String preservedAdminEmail) {
    }
}
