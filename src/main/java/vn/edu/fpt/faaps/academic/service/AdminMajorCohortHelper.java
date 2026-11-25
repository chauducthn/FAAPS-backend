package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

final class AdminMajorCohortHelper {
    private static final Logger log = LoggerFactory.getLogger(AdminMajorCohortHelper.class);

    private final JdbcTemplate jdbc;

    AdminMajorCohortHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertInitialCohort(UUID majorId, String majorName, String cohortVersion) {
        String semesterId = resolveLatestSemesterId();
        insertCurriculumVersion(majorId, majorName, cohortVersion, semesterId);
    }

    void upsertCohortForMajor(UUID majorId, String majorName, String cohortVersion) {
        List<String> matchingIds = jdbc.query(
                "SELECT id FROM curriculum_versions WHERE major_id = ? AND version = ?",
                (rs, r) -> rs.getString(1), majorId.toString(), cohortVersion);
        if (!matchingIds.isEmpty()) {
            jdbc.update("UPDATE curriculum_versions SET is_current = true, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    matchingIds.get(0));
            return;
        }
        List<String> existingVersions = jdbc.query(
                "SELECT id FROM curriculum_versions WHERE major_id = ? ORDER BY updated_at DESC, created_at DESC",
                (rs, r) -> rs.getString(1), majorId.toString());
        if (existingVersions.size() == 1) {
            String onlyId = existingVersions.get(0);
            Integer studentsUsing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM students WHERE curriculum_version_id = ?", Integer.class, onlyId);
            if (studentsUsing == null || studentsUsing == 0) {
                jdbc.update("""
                        UPDATE curriculum_versions
                        SET version = ?, name = ?, is_current = true, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, cohortVersion, majorName + " - " + cohortVersion, onlyId);
                return;
            }
        }
        insertCurriculumVersion(majorId, majorName, cohortVersion, resolveLatestSemesterId());
    }

    private String resolveLatestSemesterId() {
        try {
            List<String> sems = jdbc.query(
                    "SELECT id FROM semesters ORDER BY is_current DESC, start_date DESC LIMIT 1",
                    (rs, r) -> rs.getString(1));
            return sems.isEmpty() ? null : sems.get(0);
        } catch (Exception ex) {
            log.debug("Could not resolve latest semester for major cohort: {}", ex.getMessage());
            return null;
        }
    }

    private void insertCurriculumVersion(UUID majorId, String majorName, String cohortVersion, String semesterId) {
        String curriculumName = majorName + " - " + cohortVersion;
        if (semesterId != null) {
            try {
                jdbc.update("""
                        INSERT INTO curriculum_versions
                            (id, major_id, version, name, effective_from_semester_id,
                             is_current, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, true, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID().toString(), majorId.toString(), cohortVersion,
                        curriculumName, semesterId);
                return;
            } catch (Exception ex) {
                log.debug("Falling back to legacy curriculum_versions insert without semester: {}", ex.getMessage());
            }
        }
        jdbc.update("""
                INSERT INTO curriculum_versions
                    (id, major_id, version, name, is_current, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), majorId.toString(), cohortVersion, curriculumName);
    }
}
