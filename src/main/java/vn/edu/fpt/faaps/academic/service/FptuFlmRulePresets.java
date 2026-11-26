package vn.edu.fpt.faaps.academic.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Standard FPT University (FLM) academic policies seeded on demand for FAAPS. */
public final class FptuFlmRulePresets {

    private record Preset(String ruleName, String policyType, String conditionJson, String restrictionJson,
            boolean approvalRequired, int priority, String description) {
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset(
                    "FPTU Semester GPA Warning",
                    "GPA_WARNING",
                    "{\"rule_name\":\"FPTU Semester GPA Warning\",\"scope\":\"semester_gpa\",\"operator\":\"<\",\"min_gpa\":5.0,\"grade\":5.0}",
                    "{\"action\":\"WARN\",\"max_credits\":18,\"note\":\"Trigger academic warning and advisor outreach\"}",
                    false, 1,
                    "Warn students whose semester GPA falls below 5.0 on the 10-point FPTU grading scale."),
            new Preset(
                    "FPTU Academic Probation",
                    "ACADEMIC_PROBATION",
                    "{\"rule_name\":\"FPTU Academic Probation\",\"operator\":\"<\",\"min_gpa\":4.5,\"consecutive_semesters\":2,\"grade\":4.5}",
                    "{\"action\":\"RESTRICT\",\"max_credits\":15,\"note\":\"Mandatory advisor review and reduced credit load\"}",
                    true, 2,
                    "Place students on academic probation if GPA stays below 4.5 for 2 consecutive semesters."),
            new Preset(
                    "FPTU Standard Semester Credit Cap",
                    "MAX_CREDITS_PER_SEMESTER",
                    "{\"rule_name\":\"FPTU Standard Semester Credit Cap\",\"scope\":\"semester_registration\",\"max_credits\":24,\"grade\":24}",
                    "{\"action\":\"BLOCK\",\"max_credits\":24,\"note\":\"Overload above 24 credits requires advisor approval\"}",
                    true, 3,
                    "Limit standard semester enrollment to 24 credits (normal FLM load is 15-20 credits)."),
            new Preset(
                    "FPTU Minimum Full-Time Load",
                    "MIN_CREDITS_PER_SEMESTER",
                    "{\"rule_name\":\"FPTU Minimum Full-Time Load\",\"scope\":\"semester_registration\",\"min_credits\":12,\"full_time_only\":true,\"grade\":12}",
                    "{\"action\":\"WARN\",\"note\":\"Under-loading may delay 9-semester FLM graduation plan\"}",
                    false, 4,
                    "Full-time FPTU students should register at least 12 credits per regular semester."),
            new Preset(
                    "FPTU 80% Attendance Requirement",
                    "OTHER",
                    "{\"rule_name\":\"FPTU 80% Attendance Requirement\",\"scope\":\"course_attendance\",\"operator\":\"<\",\"min_attendance_percent\":80,\"grade\":80}",
                    "{\"action\":\"BLOCK\",\"note\":\"Exceeding 20% absences disqualifies student from Final Exam\"}",
                    false, 5,
                    "Students must attend at least 80% of course sessions (max 20% absences) to take the Final Exam."),
            new Preset(
                    "FPTU Course Passing & Retake Rule",
                    "RETAKE",
                    "{\"rule_name\":\"FPTU Course Passing & Retake Rule\",\"operator\":\"<\",\"max_score_for_retake\":5.0,\"max_attempts\":3,\"grade\":5.0}",
                    "{\"action\":\"WARN\",\"note\":\"Score below 5.0 (or Final Exam < 4.0) requires course retake\"}",
                    false, 6,
                    "Courses with total score below 5.0 fail and must be retaken (up to 3 total attempts)."),
            new Preset(
                    "FPTU Maximum Course Repeat Limit",
                    "COURSE_REPEAT_LIMIT",
                    "{\"rule_name\":\"FPTU Maximum Course Repeat Limit\",\"scope\":\"single_course\",\"max_repeats\":2,\"grade\":2}",
                    "{\"action\":\"BLOCK\",\"note\":\"Repeating a course more than twice requires Academic Affairs approval\"}",
                    true, 7,
                    "Each failed course may be repeated at most 2 times before requiring formal approval."),
            new Preset(
                    "FPTU OJT Eligibility (OJT202)",
                    "OTHER",
                    "{\"rule_name\":\"FPTU OJT Eligibility (OJT202)\",\"scope\":\"milestone\",\"min_credits\":90,\"course_code\":\"OJT202\",\"grade\":90}",
                    "{\"action\":\"BLOCK\",\"note\":\"Must complete ~90% of pre-OJT credits before Semester 6 OJT\"}",
                    true, 8,
                    "Students must accumulate at least 90 credits before enrolling in On-the-Job Training (OJT202)."),
            new Preset(
                    "FPTU Capstone Project Eligibility",
                    "OTHER",
                    "{\"rule_name\":\"FPTU Capstone Project Eligibility\",\"scope\":\"milestone\",\"min_credits\":120,\"min_gpa\":5.0,\"course_code\":\"SEP490\",\"grade\":120}",
                    "{\"action\":\"BLOCK\",\"note\":\"Must satisfy pre-capstone credits and passing GPA before Semester 9 Capstone\"}",
                    true, 9,
                    "Students must earn at least 120 credits and maintain GPA >= 5.0 before registering for Capstone."),
            new Preset(
                    "FPTU Fast-Track / Overload Study",
                    "ADVANCED_STUDY",
                    "{\"rule_name\":\"FPTU Fast-Track / Overload Study\",\"scope\":\"fast_track\",\"min_gpa\":8.0,\"min_credits_earned\":60,\"grade\":8.0}",
                    "{\"action\":\"ALLOW\",\"note\":\"Eligible for accelerated courses or credit overload with advisor approval\"}",
                    true, 10,
                    "High-achieving students (GPA >= 8.0 and >= 60 credits) may enroll in advanced or overload courses.")
    );

    private FptuFlmRulePresets() {
    }

    public record SeedResult(int created, int skipped) {
    }

    static SeedResult seedDefaults(JdbcTemplate jdbc, UUID defaultCurriculumVersionId) {
        if (defaultCurriculumVersionId == null) {
            throw new IllegalArgumentException("At least one curriculum cohort is required before loading FLM default rules.");
        }
        Set<String> existing = new HashSet<>();
        jdbc.query("SELECT condition_expression, description FROM academic_policies", rs -> {
            String cond = rs.getString("condition_expression");
            String desc = rs.getString("description");
            if (cond != null) {
                existing.add(cond.toLowerCase(Locale.ROOT));
            }
            if (desc != null) {
                existing.add(desc.strip().toLowerCase(Locale.ROOT));
            }
        });

        int created = 0;
        int skipped = 0;
        for (Preset preset : PRESETS) {
            String marker = ("\"rule_name\":\"" + preset.ruleName() + "\"").toLowerCase(Locale.ROOT);
            boolean exists = existing.stream().anyMatch(e -> e.contains(marker)
                    || e.equals(preset.description().toLowerCase(Locale.ROOT)));
            if (exists) {
                skipped++;
                continue;
            }
            jdbc.update("""
                    INSERT INTO academic_policies (id, curriculum_version_id, policy_type, condition_expression,
                        restriction_expression, approval_required, priority, description, display_message,
                        is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID().toString(), defaultCurriculumVersionId.toString(), preset.policyType(),
                    preset.conditionJson(), preset.restrictionJson(), preset.approvalRequired(),
                    preset.priority(), preset.description(), preset.ruleName());
            created++;
        }
        return new SeedResult(created, skipped);
    }
}
