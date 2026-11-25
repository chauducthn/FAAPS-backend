package vn.edu.fpt.faaps.infrastructure.config;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tracks the current existing database tables on startup (or when invoked explicitly)
 * and seeds baseline sample data if the core tables are empty.
 */
@Component
public class LocalDatabaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDatabaseSeeder.class);

    private static final List<String> TRACKED_TABLES = List.of(
            "roles", "users", "majors", "semesters", "curriculum_versions",
            "course_categories", "courses", "course_prerequisites", "curriculum_courses",
            "academic_policies", "advisors", "students", "student_advisor_assignments",
            "student_course_records", "semester_summaries", "academic_warnings",
            "study_plans", "advising_requests", "advising_sessions");

    private static final List<String> CORE_REQUIRED_TABLES = List.of(
            "majors", "semesters", "curriculum_versions", "courses",
            "academic_policies", "advisors", "students");

    private final JdbcTemplate jdbc;
    private final boolean autoEnable;

    public LocalDatabaseSeeder(
            JdbcTemplate jdbc,
            @Value("${faaps.seed.auto-enable:false}") boolean autoEnable) {
        this.jdbc = jdbc;
        this.autoEnable = autoEnable;
    }

    public record DatabaseTrackingReport(
            Map<String, Integer> beforeCounts,
            Map<String, Integer> afterCounts,
            boolean seeded) {}

    @Override
    public void run(ApplicationArguments args) {
        if (!autoEnable || !isLocalDatabaseConnection()) {
            return;
        }
        try {
            DatabaseTrackingReport report = trackAndSeedIfEmpty(false);
            if (report.seeded()) {
                log.info("FAAPS LocalDatabaseSeeder: Seeded empty local database tables. Before={}, After={}",
                        report.beforeCounts(), report.afterCounts());
            } else {
                log.info("FAAPS LocalDatabaseSeeder: Existing database data detected. Tracking snapshot={}",
                        report.beforeCounts());
            }
        } catch (Exception ex) {
            log.warn("FAAPS LocalDatabaseSeeder skipped automatic startup check: {}", ex.getMessage());
        }
    }

    /**
     * Inspects current row counts across tracked tables.
     * Missing tables are recorded with count = -1.
     */
    public Map<String, Integer> inspectTableCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : TRACKED_TABLES) {
            counts.put(table, safeCount(jdbc, table));
        }
        return counts;
    }

    /**
     * Tracks the current existing DB and seeds sample data if any core required table is empty
     * (or if force is true).
     */
    public DatabaseTrackingReport trackAndSeedIfEmpty(boolean force) {
        Map<String, Integer> before = inspectTableCounts();
        for (String core : CORE_REQUIRED_TABLES) {
            if (before.getOrDefault(core, -1) < 0) {
                return new DatabaseTrackingReport(before, before, false);
            }
        }
        boolean needsSeed = force || CORE_REQUIRED_TABLES.stream()
                .anyMatch(table -> before.getOrDefault(table, 0) == 0);
        if (!needsSeed) {
            return new DatabaseTrackingReport(before, before, false);
        }

        LocalSeedCatalogSql catalogSql = new LocalSeedCatalogSql(jdbc);
        LocalSeedCatalog.CatalogRefs refs = new LocalSeedCatalog(catalogSql, new LocalSeedCourseSql(jdbc)).seed();
        new LocalSeedPeople(catalogSql, new LocalSeedPeopleSql(jdbc), new LocalSeedAdvisingSql(jdbc)).seed(refs);

        Map<String, Integer> after = inspectTableCounts();
        return new DatabaseTrackingReport(before, after, true);
    }

    static int safeCount(JdbcTemplate jdbc, String table) {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return count == null ? 0 : count;
        } catch (Exception ex) {
            return -1;
        }
    }

    private boolean isLocalDatabaseConnection() {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            return false;
        }
        try (Connection conn = ds.getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url == null) {
                return false;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("jdbc:h2:")) {
                return false;
            }
            return lower.contains("//localhost") || lower.contains("//127.0.0.1");
        } catch (Exception ex) {
            return false;
        }
    }
}
