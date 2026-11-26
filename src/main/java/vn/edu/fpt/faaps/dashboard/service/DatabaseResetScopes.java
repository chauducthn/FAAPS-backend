package vn.edu.fpt.faaps.dashboard.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** FK-safe per-module reset definitions derived from the FAAPS MySQL schema. */
public final class DatabaseResetScopes {

    public record ScopeSpec(String id, String label, String description, List<String> tables,
            String preserves, boolean includesUsers) {
    }

    public record ScopeStats(String id, String label, String description, long rowCount,
            List<String> tables, String preserves) {
    }

    private static final List<ScopeSpec> SCOPES = List.of(
            new ScopeSpec(
                    "ATTENDANCE",
                    "Attendance & Overrides",
                    "Clears imported attendance sheets, student attendance records, and per-course overrides.",
                    List.of("student_course_attendance", "attendance_import_rows", "attendance_imports",
                            "course_attendance_configs"),
                    "Keeps default 80% rule, courses, students, and semesters.",
                    false),
            new ScopeSpec(
                    "ADVISING",
                    "Advising & Appointments",
                    "Clears advising requests, sessions, notes, messages, availability, warnings, and notifications.",
                    List.of("advising_notes", "advising_messages", "advising_sessions", "advising_requests",
                            "advisor_availability", "academic_warnings", "notifications"),
                    "Keeps students, advisors, assignments, courses, and curriculums.",
                    false),
            new ScopeSpec(
                    "ASSIGNMENTS",
                    "Advisor Assignments",
                    "Clears student-advisor and cohort-advisor assignment links.",
                    List.of("student_advisor_assignments", "cohort_advisors"),
                    "Keeps all student/advisor accounts, cohorts, and advising history.",
                    false),
            new ScopeSpec(
                    "ACADEMIC_RULES",
                    "Academic Rules",
                    "Clears all FPTU academic policies (can be reloaded anytime with Load FLM defaults).",
                    List.of("academic_policies"),
                    "Keeps majors, cohorts, curriculums, courses, and users.",
                    false),
            new ScopeSpec(
                    "AI_DATA",
                    "AI Configs, Chats & Logs",
                    "Clears AI chats, messages, reports, usage telemetry, knowledge chunks, and AI configs.",
                    List.of("ai_reported_messages", "ai_response_reports", "ai_message_sources",
                            "course_recommendations", "ai_usage_logs", "ai_messages", "ai_conversations",
                            "document_chunks", "knowledge_documents", "ai_configuration_secrets", "ai_configurations"),
                    "Keeps all users, academic structure, and advising sessions.",
                    false),
            new ScopeSpec(
                    "STUDENT_RECORDS",
                    "Transcripts & Study Plans",
                    "Clears student transcripts, study plans, course learning plans, graduation checks, and attendance.",
                    List.of("course_learning_tasks", "course_learning_plans", "study_plan_courses", "study_plans",
                            "student_transcript_import_rows", "student_transcript_imports", "student_course_records",
                            "semester_summaries", "student_requirement_status", "graduation_checks",
                            "student_course_attendance"),
                    "Keeps student/advisor accounts, majors, cohorts, and courses.",
                    false),
            new ScopeSpec(
                    "USERS",
                    "Students & Advisors",
                    "Clears all student & advisor accounts along with their dependent advising, plans, and chat records.",
                    List.of("advising_notes", "advising_messages", "advising_sessions", "advising_requests",
                            "advisor_availability", "academic_warnings", "notifications",
                            "student_advisor_assignments", "cohort_advisors",
                            "course_learning_tasks", "course_learning_plans", "study_plan_courses", "study_plans",
                            "student_transcript_import_rows", "student_transcript_imports", "student_course_records",
                            "semester_summaries", "student_requirement_status", "graduation_checks",
                            "student_course_attendance", "ai_reported_messages", "ai_response_reports",
                            "ai_message_sources", "course_recommendations", "ai_usage_logs", "ai_messages",
                            "ai_conversations", "students", "advisors", "password_reset_tokens",
                            "audit_logs", "error_logs"),
                    "Keeps your current Admin account, majors, cohorts, courses, semesters, rules, and AI configs.",
                    true),
            new ScopeSpec(
                    "COURSES",
                    "Courses & Prerequisites",
                    "Clears course catalog, prerequisites, curriculum course links, and course-linked student records.",
                    List.of("course_learning_tasks", "course_learning_plans", "study_plan_courses",
                            "student_transcript_import_rows", "student_course_records", "course_recommendations",
                            "student_course_attendance", "course_attendance_configs", "student_requirement_status",
                            "graduation_requirements", "curriculum_courses", "course_prerequisites", "courses"),
                    "Keeps all users, majors, cohorts, semesters, categories, rules, and AI configs.",
                    false),
            new ScopeSpec(
                    "CURRICULUMS",
                    "Majors, Cohorts & Curriculums",
                    "Clears majors, cohorts, curriculum courses, graduation requirements, and detaches student placements.",
                    List.of("cohort_advisors", "student_requirement_status", "graduation_requirements",
                            "graduation_checks", "academic_policies", "curriculum_courses",
                            "curriculum_versions", "majors"),
                    "Keeps all users (major/cohort cleared on students), courses, semesters, and AI configs.",
                    false)
    );

    private static final Map<String, ScopeSpec> BY_ID = new LinkedHashMap<>();

    static {
        for (ScopeSpec spec : SCOPES) {
            BY_ID.put(spec.id(), spec);
        }
    }

    private DatabaseResetScopes() {
    }

    static ScopeSpec find(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return null;
        }
        return BY_ID.get(rawScope.strip().toUpperCase(Locale.ROOT));
    }

    static List<ScopeStats> computeStats(JdbcTemplate jdbc, Set<String> existingTables) {
        return SCOPES.stream().map(spec -> {
            long total = 0;
            for (String t : spec.tables()) {
                if (existingTables.contains(t.toLowerCase(Locale.ROOT))) {
                    Long c = jdbc.queryForObject("SELECT COUNT(*) FROM `" + t + "`", Long.class);
                    total += c != null ? c : 0;
                }
            }
            return new ScopeStats(spec.id(), spec.label(), spec.description(), total, spec.tables(), spec.preserves());
        }).toList();
    }

    static long[] execute(JdbcTemplate jdbc, ScopeSpec spec, Set<String> existingTables, String keepAdminId) {
        int clearedTables = 0;
        long deletedRows = 0;

        if ("STUDENT_RECORDS".equals(spec.id()) && existingTables.contains("advising_requests")) {
            jdbc.update("UPDATE `advising_requests` SET study_plan_id = NULL WHERE study_plan_id IS NOT NULL");
        }
        if ("CURRICULUMS".equals(spec.id()) && existingTables.contains("students")) {
            jdbc.update("UPDATE `students` SET major_id = NULL, curriculum_version_id = NULL");
        }
        if (spec.includesUsers()) {
            if (existingTables.contains("ai_configurations")) {
                jdbc.update("UPDATE `ai_configurations` SET updated_by_user_id = NULL WHERE updated_by_user_id <> ?", keepAdminId);
            }
            if (existingTables.contains("knowledge_documents")) {
                jdbc.update("UPDATE `knowledge_documents` SET uploaded_by_user_id = NULL WHERE uploaded_by_user_id <> ?", keepAdminId);
            }
        }

        for (String table : spec.tables()) {
            if (!existingTables.contains(table.toLowerCase(Locale.ROOT))) {
                continue;
            }
            deletedRows += jdbc.update("DELETE FROM `" + table + "`");
            clearedTables++;
        }

        if (spec.includesUsers()) {
            if (existingTables.contains("refresh_tokens")) {
                deletedRows += jdbc.update("DELETE FROM `refresh_tokens` WHERE user_id <> ?", keepAdminId);
                clearedTables++;
            }
            if (existingTables.contains("admin_account")) {
                deletedRows += jdbc.update("DELETE FROM `admin_account` WHERE user_id <> ?", keepAdminId);
                clearedTables++;
            }
            if (existingTables.contains("users")) {
                deletedRows += jdbc.update("DELETE FROM `users` WHERE id <> ?", keepAdminId);
                clearedTables++;
            }
        }

        if ("ATTENDANCE".equals(spec.id()) && existingTables.contains("attendance_default_config")) {
            jdbc.update("UPDATE `attendance_default_config` SET total_sessions = 20, min_attendance_percent = 80, warn_absent_sessions = 3");
        }

        return new long[] {clearedTables, deletedRows};
    }
}
