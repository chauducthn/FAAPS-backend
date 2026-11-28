package vn.edu.fpt.faaps.ai.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

/**
 * Keeps one roadmap per version kind for each student, and which one the student follows.
 *
 * <ul>
 *   <li>{@code SYSTEM} — the rule-based plan;</li>
 *   <li>{@code AI} — the AI refinement of the system plan;</li>
 *   <li>{@code CUSTOM} — the student's own arrangement, started from SYSTEM or AI.</li>
 * </ul>
 */
final class StudentRoadmapVersionStore {
    static final String SYSTEM = "SYSTEM";
    static final String AI = "AI";
    static final String CUSTOM = "CUSTOM";
    static final Set<String> KINDS = Set.of(SYSTEM, AI, CUSTOM);

    private static final Logger log = LoggerFactory.getLogger(StudentRoadmapVersionStore.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JdbcTemplate jdbc;
    private volatile boolean tableReady = false;

    StudentRoadmapVersionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // Created up front: DDL inside a transaction would commit it early on MySQL.
        ensureTable();
    }

    record Version(String kind, StudyRoadmapDto roadmap, String basedOn, boolean active) {}

    static String normalizeKind(String kind) {
        String upper = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(upper)) {
            throw new IllegalArgumentException("kind must be SYSTEM, AI or CUSTOM");
        }
        return upper;
    }

    List<Version> all(UUID studentId) {
        ensureTable();
        try {
            return jdbc.query("""
                    SELECT kind, payload, based_on, is_active FROM student_roadmap_versions WHERE student_id = ?
                    """, (rs, row) -> new Version(rs.getString("kind"), read(rs.getString("payload")),
                    rs.getString("based_on"), rs.getBoolean("is_active")), studentId.toString())
                    .stream().filter(v -> v.roadmap() != null).toList();
        } catch (RuntimeException ex) {
            log.debug("Could not read roadmap versions: {}", ex.getMessage());
            return List.of();
        }
    }

    Version find(UUID studentId, String kind) {
        return all(studentId).stream().filter(v -> v.kind().equals(kind)).findFirst().orElse(null);
    }

    String activeKind(UUID studentId) {
        return all(studentId).stream().filter(Version::active).map(Version::kind).findFirst().orElse(null);
    }

    /** Replaces the version of this kind, keeping whether it is the active one. */
    void put(UUID studentId, String kind, StudyRoadmapDto roadmap, String basedOn) {
        ensureTable();
        boolean active = kind.equals(activeKind(studentId));
        jdbc.update("DELETE FROM student_roadmap_versions WHERE student_id = ? AND kind = ?", studentId.toString(), kind);
        jdbc.update("""
                INSERT INTO student_roadmap_versions (student_id, kind, payload, based_on, is_active, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, studentId.toString(), kind, write(roadmap), basedOn, active);
    }

    void activate(UUID studentId, String kind) {
        jdbc.update("UPDATE student_roadmap_versions SET is_active = (kind = ?) WHERE student_id = ?",
                kind, studentId.toString());
    }

    private void ensureTable() {
        if (tableReady) {
            return;
        }
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS student_roadmap_versions (
                        student_id CHAR(36) NOT NULL,
                        kind VARCHAR(10) NOT NULL,
                        payload LONGTEXT NOT NULL,
                        based_on VARCHAR(10) NULL,
                        is_active BOOLEAN NOT NULL DEFAULT FALSE,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (student_id, kind)
                    )
                    """);
            tableReady = true;
        } catch (Exception ex) {
            log.debug("Could not create student_roadmap_versions: {}", ex.getMessage());
        }
    }

    private static String write(StudyRoadmapDto roadmap) {
        try {
            return JSON.writeValueAsString(roadmap);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not store the roadmap", ex);
        }
    }

    private static StudyRoadmapDto read(String payload) {
        try {
            return payload == null ? null : JSON.readValue(payload, StudyRoadmapDto.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
