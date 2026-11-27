package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload.HistoricalStats;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload.WorkloadInput;

/**
 * Pure 3-layer course workload evaluator.
 * Layer 1: Rule-based automatic score from category, credits, grading structure, and prerequisite graph.
 * Layer 2: Manual override by Admin/Advisor.
 * Layer 3: Historical student performance adjustment when sample size >= 30.
 */
public final class CourseWorkloadEvaluator {
    private static final int BASE_SCORE = 35;
    private static final int HEAVY_THRESHOLD = 65;
    private static final int MEDIUM_THRESHOLD = 38;

    private static final Pattern LIGHT_CODE_PREFIX = Pattern.compile(
            "^(VOV|TMI|TRS|LUK|PEN|PHE|SSL|SSG|SSB|ENT|OJT|GDQP|QPAN)[0-9_A-Z]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COURSERA_SUFFIX = Pattern.compile("^[A-Z]{2,4}\\d{3}c$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PE_PATTERN = Pattern.compile("\\b(pe|practical\\s+exam)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FE_PATTERN = Pattern.compile("\\b(fe|final\\s+exam)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_FINAL_EXAM_PATTERN = Pattern.compile("\\bno\\s+(final\\s+exam|fe)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROJECT_GRADING_PATTERN = Pattern.compile(
            "\\b(project|capstone|assignment|lab|workshop)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIGHT_GRADING_PATTERN = Pattern.compile(
            "(pass\\s*/\\s*fail|pass\\s+fail|coursera|mooc|completion|no\\s+final\\s+exam|no\\s+fe)", Pattern.CASE_INSENSITIVE);

    private CourseWorkloadEvaluator() {
    }

    public static CourseWorkload evaluate(WorkloadInput input, HistoricalStats rawStats) {
        List<String> reasons = new ArrayList<>();
        int ruleScore = BASE_SCORE
                + scoreCategory(input, reasons)
                + scoreCredits(input.credits(), reasons)
                + scoreGrading(input.gradingStructure(), reasons)
                + scorePrerequisites(input.prerequisiteCount(), input.prerequisiteDepth(), input.unlockCount(), reasons);
        ruleScore = clamp(ruleScore, 0, 100);
        String ruleLevel = toLevel(ruleScore);

        HistoricalStats stats = rawStats == null ? HistoricalStats.empty() : rawStats;
        int effectiveScore = stats.sufficientSample() ? clamp(ruleScore + stats.dataAdjustment(), 0, 100) : ruleScore;
        String manual = safeNormalizeManual(input.manualLevel());

        if (manual != null) {
            return new CourseWorkload(manual, effectiveScore, CourseWorkload.SOURCE_MANUAL,
                    manual, ruleLevel, ruleScore, List.copyOf(reasons), stats);
        }
        String source = stats.sufficientSample()
                ? CourseWorkload.SOURCE_DATA_ADJUSTED
                : CourseWorkload.SOURCE_RULE_BASED;
        return new CourseWorkload(toLevel(effectiveScore), effectiveScore, source,
                null, ruleLevel, ruleScore, List.copyOf(reasons), stats);
    }

    private static int scoreCategory(WorkloadInput input, List<String> reasons) {
        String combined = joinLower(input.categoryCode(), input.categoryName(), input.knowledgeBlock(), input.name());
        String code = input.code() == null ? "" : input.code().strip();
        int delta = 0;

        if (containsAny(combined, "capstone", "graduation", "thesis", "project")) {
            delta += 20;
            reasons.add("Project / capstone intensive");
        } else if (LIGHT_CODE_PREFIX.matcher(code).matches()
                || containsAny(combined, "vovinam", "physical", "music", "instrument", "soft skill",
                        "general education", "prep", "english", "ojt", "internship")) {
            delta -= 18;
            reasons.add("General / soft-skill / practice category");
        } else if (containsAny(combined, "core", "major", "special", "combo")) {
            delta += 12;
            reasons.add("Core / major specialization");
        } else if (containsAny(combined, "foundation", "math", "basic", "fundamental")) {
            delta += 6;
            reasons.add("Foundation / quantitative subject");
        }

        if (COURSERA_SUFFIX.matcher(code).matches()) {
            delta -= 10;
            reasons.add("Coursera / MOOC format");
        }
        return delta;
    }

    private static int scoreCredits(int credits, List<String> reasons) {
        if (credits >= 6) {
            reasons.add(credits + " credits (high credit load)");
            return 18;
        }
        if (credits >= 4) {
            reasons.add(credits + " credits (above standard)");
            return 10;
        }
        if (credits == 3) {
            reasons.add("3 credits (standard)");
            return 4;
        }
        if (credits == 2) {
            reasons.add("2 credits (light load)");
            return -4;
        }
        reasons.add(credits + " credit(s) (minimal credit load)");
        return -12;
    }

    private static int scoreGrading(String gradingStructure, List<String> reasons) {
        if (gradingStructure == null || gradingStructure.isBlank()) {
            return 0;
        }
        int delta = 0;
        String strippedNoFe = NO_FINAL_EXAM_PATTERN.matcher(gradingStructure).replaceAll(" ");
        boolean hasPe = PE_PATTERN.matcher(gradingStructure).find();
        boolean hasFe = FE_PATTERN.matcher(strippedNoFe).find();
        if (hasPe && hasFe) {
            delta += 18;
            reasons.add("Both Practical Exam (PE) and Final Exam (FE)");
        } else if (hasPe) {
            delta += 12;
            reasons.add("Practical Exam (PE) assessment");
        } else if (hasFe) {
            delta += 6;
            reasons.add("Final Exam (FE) assessment");
        }
        if (PROJECT_GRADING_PATTERN.matcher(gradingStructure).find()) {
            delta += 8;
            reasons.add("Includes lab / project / assignment deliverables");
        }
        if (LIGHT_GRADING_PATTERN.matcher(gradingStructure).find()) {
            delta -= 12;
            reasons.add("Pass/Fail or Coursera completion grading");
        }
        return delta;
    }

    private static int scorePrerequisites(int prereqCount, int prereqDepth, int unlockCount, List<String> reasons) {
        int delta = 0;
        if (prereqCount >= 3 || prereqDepth >= 3) {
            delta += 12;
            reasons.add("Deep prerequisite chain (" + prereqCount + " direct, depth " + prereqDepth + ")");
        } else if (prereqCount == 2 || prereqDepth == 2) {
            delta += 7;
            reasons.add("Multi-stage prerequisites (" + prereqCount + " direct)");
        } else if (prereqCount == 1) {
            delta += 3;
            reasons.add("1 prerequisite required");
        }
        if (unlockCount >= 3) {
            delta += 8;
            reasons.add("Gateway course unlocking " + unlockCount + " downstream courses");
        } else if (unlockCount >= 1) {
            delta += 3;
            reasons.add("Unlocks " + unlockCount + " downstream course(s)");
        }
        return delta;
    }

    private static String toLevel(int score) {
        if (score >= HEAVY_THRESHOLD) return CourseWorkload.LEVEL_HEAVY;
        if (score >= MEDIUM_THRESHOLD) return CourseWorkload.LEVEL_MEDIUM;
        return CourseWorkload.LEVEL_LIGHT;
    }

    private static String safeNormalizeManual(String raw) {
        try {
            return CourseWorkload.normalizeManualLevel(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private static String joinLower(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(v.toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
