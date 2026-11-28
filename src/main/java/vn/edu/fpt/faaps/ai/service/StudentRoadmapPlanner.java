package vn.edu.fpt.faaps.ai.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapGoalCheckDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;

/**
 * Turns the courses a student still has to take into a term-by-term plan.
 *
 * <p>The planner is pure logic so it can be tested without a database. It
 * <ul>
 *   <li>schedules failed courses first, so the retake does not block later courses;</li>
 *   <li>only schedules a course once its prerequisites are passed or planned in an earlier term
 *       (rows in the same prerequisite group are alternatives, different groups are all required);</li>
 *   <li>spreads the remaining credits over the terms left until the target graduation term, never above the
 *       credit limit;</li>
 *   <li>gives OJT a term of its own, as FPT University does;</li>
 *   <li>labels every term with a real code (SP, SU, FA) and suggests a study method per course and per term.</li>
 * </ul>
 */
final class StudentRoadmapPlanner {

    private static final Pattern TERM_CODE = Pattern.compile("^(SP|SU|FA)(\\d{2}|\\d{4})$");
    private static final List<String> SEASONS = List.of("SP", "SU", "FA");
    private static final int MAX_TERMS = 30;
    /** A course may be pulled at most this many curriculum semesters ahead of the earliest one still open. */
    private static final int LOOKAHEAD = 2;

    private static final Set<String> CODING_PREFIXES = Set.of("PRF", "PRO", "PRJ", "PRN", "PRM", "CSD", "DBI",
            "SWP", "SWT", "SWE", "SWR", "SWD", "SWM", "WED", "WEB", "LAB", "IOT", "AIL", "AIP", "DAP", "DPL", "CPV",
            "MAD", "JPD");
    private static final Set<String> MATH_PREFIXES = Set.of("MAE", "MAS", "MAI", "MAA", "MAC", "MAT");
    private static final Set<String> LANGUAGE_PREFIXES = Set.of("ENT", "ENW", "ENM", "TRS", "JPD", "JPS",
            "KOR", "CHN", "VOV", "EAP", "EAW");
    private static final Set<String> THEORY_PREFIXES = Set.of("MLN", "VNR", "HCM", "PHE", "SSL", "SSG", "SSC",
            "OBE", "ECO", "ACC", "MKT", "LAW", "ITE", "NWC", "OSG", "CEA", "SEC", "IAO");

    private StudentRoadmapPlanner() {}

    /** A course the student still has to complete. */
    record PlannerCourse(
            String id,
            String code,
            String name,
            int credits,
            String category,
            boolean mandatory,
            int curriculumSemester,
            boolean retake,
            Double lastScore,
            /** Prerequisite course ids, grouped: any id inside a group satisfies it, every group is required. */
            List<List<String>> prerequisiteGroups,
            List<String> prerequisiteCodes) {}

    record PlannerInput(
            List<PlannerCourse> courses,
            /** Course ids that already count as done (passed, exempted or still in progress). */
            Set<String> satisfiedCourseIds,
            String startTerm,
            String targetGraduationTerm,
            int maxCreditsPerTerm,
            String orientation,
            Double currentGpa,
            Double targetGpa,
            int passedCredits) {}

    record PlannerResult(List<RoadmapSemesterDto> semesters, RoadmapGoalCheckDto goalCheck,
            List<String> warnings, List<String> studyMethods) {}

    static PlannerResult plan(PlannerInput input) {
        List<String> warnings = new ArrayList<>();
        List<PlannerCourse> open = new ArrayList<>(input.courses());
        int remainingCredits = open.stream().mapToInt(PlannerCourse::credits).sum();
        int maxCredits = Math.max(1, input.maxCreditsPerTerm());

        Integer termsUntilTarget = input.targetGraduationTerm() == null ? null
                : termDistance(input.startTerm(), input.targetGraduationTerm());
        if (termsUntilTarget != null && termsUntilTarget < 1 && !open.isEmpty()) {
            warnings.add("Your target graduation term " + input.targetGraduationTerm()
                    + " is before the next term you can register (" + input.startTerm() + ").");
        }
        int load = recommendedLoad(remainingCredits, termsUntilTarget, maxCredits);

        Map<String, Integer> unlocks = unlockCounts(open);
        Set<String> planIds = new HashSet<>();
        open.forEach(course -> planIds.add(course.id()));
        open.sort(priorityOrder(unlocks));

        Map<String, Integer> plannedTerm = new HashMap<>();
        List<List<PlannerCourse>> terms = new ArrayList<>();
        Set<String> forced = new LinkedHashSet<>();
        while (!open.isEmpty() && terms.size() < MAX_TERMS) {
            int termIndex = terms.size();
            // Re-balance every term so a light term (blocked by prerequisites) is caught up later.
            int openCredits = open.stream().mapToInt(PlannerCourse::credits).sum();
            int termLoad = termsUntilTarget == null ? load
                    : recommendedLoad(openCredits, termsUntilTarget - termIndex, maxCredits);
            List<PlannerCourse> picked = pickTerm(open, termIndex, termLoad, maxCredits, input.satisfiedCourseIds(),
                    planIds, plannedTerm);
            if (picked.isEmpty()) {
                // Nothing is unlocked: the prerequisites can never be met inside this plan. Schedule the first open
                // course anyway so the plan finishes, and tell the student.
                PlannerCourse first = open.get(0);
                picked = List.of(first);
                forced.add(first.code());
            }
            for (PlannerCourse course : picked) {
                plannedTerm.put(course.id(), termIndex);
                open.remove(course);
            }
            terms.add(picked);
        }
        if (!forced.isEmpty()) {
            warnings.add("Prerequisites for " + String.join(", ", forced)
                    + " are not met by any passed or planned course. Check them with your advisor.");
        }

        List<RoadmapSemesterDto> semesters = new ArrayList<>();
        String term = input.startTerm();
        for (int i = 0; i < terms.size(); i++) {
            semesters.add(toSemester(i + 1, term, terms.get(i), unlocks, maxCredits, input));
            term = nextTerm(term);
        }

        String projected = semesters.isEmpty() ? null : semesters.get(semesters.size() - 1).semesterCode();
        Boolean onTrack = null;
        if (input.targetGraduationTerm() != null && projected != null) {
            Integer distance = termDistance(projected, input.targetGraduationTerm());
            onTrack = distance != null && distance >= 1;
            if (!onTrack) {
                warnings.add("At up to " + maxCredits + " credits per term you would graduate in " + projected
                        + ", later than your target " + input.targetGraduationTerm()
                        + ". Consider a summer term or ask your advisor about the credit limit.");
            }
        }

        Double required = requiredAverage(input.currentGpa(), input.passedCredits(), remainingCredits,
                input.targetGpa());
        if (required != null && required > 10.0) {
            warnings.add(String.format(Locale.ROOT,
                    "A GPA of %.2f is out of reach: even 10/10 on every remaining course is not enough. "
                            + "Set a target you can reach to keep the plan useful.", input.targetGpa()));
        } else if (required != null && required >= 8.5) {
            warnings.add(String.format(Locale.ROOT,
                    "To reach GPA %.2f you need about %.2f on every remaining course. Keep a lighter load "
                            + "in heavy terms.", input.targetGpa(), required));
        }

        int retakes = (int) input.courses().stream().filter(PlannerCourse::retake).count();
        RoadmapGoalCheckDto check = new RoadmapGoalCheckDto(input.startTerm(), input.targetGraduationTerm(),
                projected, onTrack, remainingCredits, load, input.currentGpa(), input.targetGpa(),
                required == null ? null : Math.round(Math.min(required, 99.0) * 100.0) / 100.0, retakes);
        return new PlannerResult(semesters, check, warnings, studyMethods(input, retakes, required));
    }

    private static List<PlannerCourse> pickTerm(List<PlannerCourse> open, int termIndex, int load, int maxCredits,
            Set<String> satisfied, Set<String> planIds, Map<String, Integer> plannedTerm) {
        int earliestOpen = open.stream().mapToInt(PlannerCourse::curriculumSemester).min().orElse(0);
        List<PlannerCourse> picked = new ArrayList<>();
        int[] credits = {0};
        // First pass keeps the curriculum order (a short look-ahead); the second fills the spare credits.
        for (int pass = 0; pass < 2; pass++) {
            for (PlannerCourse course : open) {
                if (picked.contains(course) || !prerequisitesMet(course, termIndex, satisfied, planIds, plannedTerm)) {
                    continue;
                }
                if (pass == 0 && !course.retake() && course.curriculumSemester() > earliestOpen + LOOKAHEAD) {
                    continue;
                }
                if (isOjt(course)) {
                    if (picked.isEmpty()) {
                        return List.of(course);
                    }
                    continue;
                }
                if (!picked.isEmpty()
                        && (credits[0] + course.credits() > load || credits[0] + course.credits() > maxCredits)) {
                    continue;
                }
                picked.add(course);
                credits[0] += course.credits();
            }
        }
        return picked;
    }

    private static boolean prerequisitesMet(PlannerCourse course, int termIndex, Set<String> satisfied,
            Set<String> planIds, Map<String, Integer> plannedTerm) {
        for (List<String> group : course.prerequisiteGroups()) {
            boolean groupMet = false;
            boolean groupReachable = false;
            for (String prerequisite : group) {
                Integer term = plannedTerm.get(prerequisite);
                if (satisfied.contains(prerequisite) || (term != null && term < termIndex)) {
                    groupMet = true;
                    break;
                }
                if (planIds.contains(prerequisite)) {
                    groupReachable = true;
                }
            }
            // A prerequisite outside the curriculum and the transcript cannot be planned; do not let it block.
            if (!groupMet && groupReachable) {
                return false;
            }
        }
        return true;
    }

    private static Comparator<PlannerCourse> priorityOrder(Map<String, Integer> unlocks) {
        return Comparator.comparing((PlannerCourse c) -> c.retake() ? 0 : 1)
                .thenComparingInt(PlannerCourse::curriculumSemester)
                .thenComparing(c -> c.mandatory() ? 0 : 1)
                .thenComparing(c -> -unlocks.getOrDefault(c.id(), 0))
                .thenComparing(PlannerCourse::code, Comparator.nullsLast(String::compareTo));
    }

    private static Map<String, Integer> unlockCounts(List<PlannerCourse> courses) {
        Map<String, Integer> counts = new HashMap<>();
        for (PlannerCourse course : courses) {
            for (List<String> group : course.prerequisiteGroups()) {
                for (String prerequisite : group) {
                    counts.merge(prerequisite, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static int recommendedLoad(int remainingCredits, Integer termsUntilTarget, int maxCredits) {
        if (remainingCredits <= 0) {
            return maxCredits;
        }
        if (termsUntilTarget == null || termsUntilTarget < 1) {
            return maxCredits;
        }
        int balanced = (int) Math.ceil(remainingCredits / (double) termsUntilTarget);
        // A very light term wastes a semester's fees; keep at least a normal part-time load when it fits.
        return Math.max(Math.min(10, maxCredits), Math.min(balanced, maxCredits));
    }

    private static RoadmapSemesterDto toSemester(int number, String term, List<PlannerCourse> courses,
            Map<String, Integer> unlocks, int maxCredits, PlannerInput input) {
        List<RoadmapCourseDto> dtos = new ArrayList<>();
        int credits = 0;
        for (PlannerCourse course : courses) {
            credits += course.credits();
            dtos.add(toCourse(course, unlocks.getOrDefault(course.id(), 0), input.orientation()));
        }
        String note = credits + " credits · " + courses.size() + (courses.size() == 1 ? " course" : " courses")
                + " · limit " + maxCredits;
        return new RoadmapSemesterDto(number, term, termTitle(term), credits, number == 1 ? "PLANNED" : "SUGGESTED",
                dtos, note, termMethod(courses, input));
    }

    private static RoadmapCourseDto toCourse(PlannerCourse course, int unlockCount, String orientation) {
        String priority = course.retake() || unlockCount >= 2 ? "HIGH" : course.mandatory() ? "MEDIUM" : "LOW";
        List<String> reasons = new ArrayList<>();
        if (course.retake()) {
            reasons.add(course.lastScore() == null ? "Retake: you did not pass this course yet."
                    : String.format(Locale.ROOT, "Retake: last score %.1f.", course.lastScore()));
        }
        reasons.add(course.mandatory() ? "Mandatory course of your curriculum (semester " + course.curriculumSemester()
                + ")." : "Elective that counts toward your specialization.");
        if (unlockCount > 0) {
            reasons.add("Prerequisite for " + unlockCount + (unlockCount == 1 ? " later course." : " later courses."));
        }
        if (orientation != null && !orientation.isBlank()) {
            reasons.add("Fits your " + orientation.toUpperCase(Locale.ROOT).replace('_', ' ') + " track.");
        }
        return new RoadmapCourseDto(course.id(), course.code(), course.name(), course.credits(), course.category(),
                course.mandatory(), priority, "PLANNED", String.join(" ", reasons), course.prerequisiteCodes(),
                course.retake(), courseTip(course));
    }

    static String courseTip(PlannerCourse course) {
        String tip;
        String prefix = prefixOf(course.code());
        if (isOjt(course)) {
            tip = "Treat OJT as a real job: update your CV and portfolio one term ahead and keep a weekly work log.";
        } else if (isCapstone(course)) {
            tip = "Form your team early, freeze the scope by week 2 and demo working software every sprint.";
        } else if (CODING_PREFIXES.contains(prefix)) {
            tip = "Code a little every day, finish each lab before the deadline and keep your work in Git.";
        } else if (MATH_PREFIXES.contains(prefix)) {
            tip = "Solve problem sets every week and redo past progress tests under time limits.";
        } else if (LANGUAGE_PREFIXES.contains(prefix)) {
            tip = "Practice 20 minutes a day and speak or write in every class; attendance counts heavily.";
        } else if (THEORY_PREFIXES.contains(prefix)) {
            tip = "Keep short notes per lesson, review them weekly and prepare group presentations early.";
        } else {
            tip = "Review the slides after each class, keep attendance above 80% and start assignments in week 1.";
        }
        if (course.retake()) {
            tip += " For the retake, find which assessment cost you the pass and practise that format first.";
        }
        return tip;
    }

    private static String termMethod(List<PlannerCourse> courses, PlannerInput input) {
        if (courses.size() == 1 && isOjt(courses.get(0))) {
            return "OJT term: no other courses. Use the evenings to prepare for the capstone topic.";
        }
        long coding = courses.stream().filter(c -> CODING_PREFIXES.contains(prefixOf(c.code()))).count();
        boolean hasRetake = courses.stream().anyMatch(PlannerCourse::retake);
        List<String> parts = new ArrayList<>();
        if (hasRetake) {
            parts.add("Clear the retake first; give it your best study hours.");
        }
        if (coding >= 3) {
            parts.add("Heavy coding term: block two hours a day for labs and start assignments in week 1.");
        } else if (courses.stream().anyMatch(StudentRoadmapPlanner::isCapstone)) {
            parts.add("Capstone term: meet your team twice a week and keep other courses light.");
        } else {
            parts.add("Balanced term: plan a weekly review for each course and track deadlines in one calendar.");
        }
        if (input.targetGpa() != null && input.targetGpa() >= 8.0) {
            parts.add("Aim for 8+ on progress tests; they are cheaper to win than the final exam.");
        }
        return String.join(" ", parts);
    }

    private static List<String> studyMethods(PlannerInput input, int retakes, Double required) {
        List<String> methods = new ArrayList<>();
        String orientation = input.orientation() == null ? "" : input.orientation().strip().toUpperCase(Locale.ROOT);
        methods.add(switch (orientation) {
            case "JOB" -> "Job track: favour project work, build one portfolio project per term and prepare OJT early.";
            case "ACADEMIC" -> "Academic track: master the theory of core courses and review past exams weekly.";
            case "RESEARCH" -> "Research track: pair core courses with seminars and join a lecturer's research group.";
            case "ENTREPRENEURIAL" -> "Entrepreneur track: use EXE and capstone to validate a real product idea.";
            default -> "Clear prerequisites early and balance heavy coding courses with general subjects.";
        });
        if (retakes > 0) {
            methods.add("You have " + retakes + (retakes == 1 ? " course" : " courses")
                    + " to retake. They are planned first because later courses depend on them.");
        }
        if (required != null && required <= 10.0) {
            methods.add(String.format(Locale.ROOT,
                    "Average %.2f or more on the remaining courses to reach your target GPA.", Math.max(0, required)));
        }
        methods.add("Keep attendance above 80% in every course; missing more fails the course regardless of scores.");
        methods.add("Book a session with your advisor before each registration window to review this plan.");
        return methods;
    }

    static Double requiredAverage(Double currentGpa, int passedCredits, int remainingCredits, Double targetGpa) {
        if (targetGpa == null || remainingCredits <= 0) {
            return null;
        }
        double earned = currentGpa == null ? 0.0 : currentGpa * Math.max(0, passedCredits);
        return (targetGpa * (passedCredits + remainingCredits) - earned) / remainingCredits;
    }

    // ---- term codes -------------------------------------------------------------------------------------------

    /** Normalizes SP26 / SP2026 / sp2026 to SP2026, or returns null when the code is not a term code. */
    static String normalizeTerm(String code) {
        if (code == null) {
            return null;
        }
        Matcher matcher = TERM_CODE.matcher(code.strip().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        String year = matcher.group(2);
        return matcher.group(1) + (year.length() == 2 ? "20" + year : year);
    }

    static String nextTerm(String term) {
        String normalized = normalizeTerm(term);
        if (normalized == null) {
            return term;
        }
        int season = SEASONS.indexOf(normalized.substring(0, 2));
        int year = Integer.parseInt(normalized.substring(2));
        return season == 2 ? "SP" + (year + 1) : SEASONS.get(season + 1) + year;
    }

    /** Number of terms from {@code from} to {@code to}, both included; null when either is not a term code. */
    static Integer termDistance(String from, String to) {
        Integer a = termOrdinal(from);
        Integer b = termOrdinal(to);
        return a == null || b == null ? null : b - a + 1;
    }

    private static Integer termOrdinal(String term) {
        String normalized = normalizeTerm(term);
        if (normalized == null) {
            return null;
        }
        return Integer.parseInt(normalized.substring(2)) * 3 + SEASONS.indexOf(normalized.substring(0, 2));
    }

    /** The term that contains the given month: January–April spring, May–August summer, otherwise fall. */
    static String termOf(int year, int month) {
        return (month <= 4 ? "SP" : month <= 8 ? "SU" : "FA") + year;
    }

    private static String termTitle(String term) {
        String normalized = normalizeTerm(term);
        if (normalized == null) {
            return term;
        }
        String season = switch (normalized.substring(0, 2)) {
            case "SP" -> "Spring";
            case "SU" -> "Summer";
            default -> "Fall";
        };
        return season + " " + normalized.substring(2);
    }

    private static String prefixOf(String code) {
        if (code == null) {
            return "";
        }
        String upper = code.strip().toUpperCase(Locale.ROOT);
        return upper.length() >= 3 ? upper.substring(0, 3) : upper;
    }

    static boolean isOjt(PlannerCourse course) {
        return prefixOf(course.code()).equals("OJT")
                || (course.category() != null && course.category().toUpperCase(Locale.ROOT).contains("OJT"));
    }

    private static boolean isCapstone(PlannerCourse course) {
        String code = course.code() == null ? "" : course.code().toUpperCase(Locale.ROOT);
        String category = course.category() == null ? "" : course.category().toUpperCase(Locale.ROOT);
        // "Specialization & Capstone" is a category of ordinary electives; only the project itself counts.
        return code.endsWith("490") || code.endsWith("491") || code.contains("_GRA")
                || category.equals("CAP") || category.contains("CAPSTONE PROJECT");
    }
}
