package vn.edu.fpt.faaps.advising.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import vn.edu.fpt.faaps.advising.service.AdvisingConversationService.Request;

/** Maps advising request rows, marking major-group affinity and participation for the viewing advisor. */
final class AdvisingRequestMapper {
    private AdvisingRequestMapper() {}

    /** The viewing advisor; {@link #NONE} for a student, who never sees affinity or participation flags. */
    record Viewer(UUID advisorId, String majorGroupId, Set<String> participatedIds) {
        static final Viewer NONE = new Viewer(null, null, Set.of());

        boolean participated(String requestId, String requestAdvisorId) {
            return advisorId != null && (advisorId.toString().equalsIgnoreCase(requestAdvisorId)
                    || participatedIds.contains(requestId));
        }

        boolean sameGroup(String studentMajorGroupId) {
            return majorGroupId != null && majorGroupId.equalsIgnoreCase(studentMajorGroupId);
        }
    }

    /** Row selected with student major, major group and the linked appointment. */
    static Request enriched(ResultSet rs, Viewer viewer) throws SQLException {
        String id = rs.getString("id");
        String advisorId = rs.getString("advisor_id");
        String studentGroupId = rs.getString("student_major_group_id");
        return new Request(id, rs.getString("student_id"), rs.getString("student_name"),
                rs.getString("student_code"), rs.getString("student_major_code"), rs.getString("student_major_name"),
                studentGroupId, rs.getString("student_major_group_code"), rs.getString("student_major_group_name"),
                viewer.sameGroup(studentGroupId), advisorId, rs.getString("advisor_name"),
                viewer.participated(id, advisorId), rs.getString("study_plan_id"),
                rs.getString("linked_appointment_id"), rs.getString("title"), rs.getString("description"),
                rs.getString("request_type"), rs.getString("status"), rs.getString("resolution_summary"),
                time(rs.getTimestamp("resolved_at")), time(rs.getTimestamp("created_at")),
                time(rs.getTimestamp("updated_at")));
    }

    /** Row from a schema without major groups or appointment links. */
    static Request basic(ResultSet rs, Viewer viewer) throws SQLException {
        String id = rs.getString("id");
        String advisorId = rs.getString("advisor_id");
        return new Request(id, rs.getString("student_id"), rs.getString("student_name"),
                rs.getString("student_code"), null, null, null, null, null, false,
                advisorId, rs.getString("advisor_name"), viewer.participated(id, advisorId),
                rs.getString("study_plan_id"), null, rs.getString("title"), rs.getString("description"),
                rs.getString("request_type"), rs.getString("status"), rs.getString("resolution_summary"),
                time(rs.getTimestamp("resolved_at")), time(rs.getTimestamp("created_at")),
                time(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime time(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
