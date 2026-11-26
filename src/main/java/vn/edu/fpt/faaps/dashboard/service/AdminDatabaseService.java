package vn.edu.fpt.faaps.dashboard.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.dashboard.service.DatabaseResetScopes.ScopeSpec;
import vn.edu.fpt.faaps.dashboard.service.DatabaseResetScopes.ScopeStats;

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
        Set<String> tableSet = toLowerSet(tables);
        String adminEmail = jdbc.query(
                "SELECT email FROM users WHERE id = ?",
                (rs, n) -> rs.getString("email"),
                currentAdminId.toString()).stream().findFirst().orElse("admin");
        List<ScopeStats> scopes = DatabaseResetScopes.computeStats(jdbc, tableSet);
        return new DatabaseOverview(
                tables.size(), users, students, advisors, majors, cohorts, courses, semesters, adminEmail, scopes);
    }

    @Transactional
    public ResetResult resetDatabase(UUID keepAdminId) {
        return resetScope(keepAdminId, "ALL");
    }

    @Transactional
    public ResetResult resetScope(UUID keepAdminId, String scope) {
        String adminId = keepAdminId.toString();
        String adminEmail = jdbc.query(
                "SELECT email FROM users WHERE id = ?",
                (rs, n) -> rs.getString("email"),
                adminId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Current admin account not found"));

        String normalized = scope == null || scope.isBlank() ? "ALL" : scope.strip().toUpperCase(Locale.ROOT);
        List<String> tables = listBaseTables();
        Set<String> tableSet = toLowerSet(tables);

        if (!"ALL".equals(normalized)) {
            ScopeSpec spec = DatabaseResetScopes.find(normalized);
            if (spec == null) {
                throw new IllegalArgumentException("Unknown reset scope: " + scope);
            }
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                long[] counts = DatabaseResetScopes.execute(jdbc, spec, tableSet, adminId);
                return new ResetResult(spec.id(), (int) counts[0], counts[1], adminEmail);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }

        int clearedTables = 0;
        long deletedRows = 0;
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                String lower = table.toLowerCase(Locale.ROOT);
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
        return new ResetResult("ALL", clearedTables, deletedRows, adminEmail);
    }

    private List<String> listBaseTables() {
        return jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME",
                String.class);
    }

    private static Set<String> toLowerSet(List<String> tables) {
        Set<String> out = new HashSet<>();
        for (String t : tables) {
            out.add(t.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
        return value != null ? value : 0L;
    }

    public record DatabaseOverview(
            int totalTables, long users, long students, long advisors,
            long majors, long cohorts, long courses, long semesters,
            String preservedAdminEmail, List<ScopeStats> scopes) {
    }

    public record ResetResult(String scope, int clearedTables, long deletedRows, String preservedAdminEmail) {
    }
}
