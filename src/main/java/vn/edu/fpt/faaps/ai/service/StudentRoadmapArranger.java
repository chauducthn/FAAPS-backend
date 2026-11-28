package vn.edu.fpt.faaps.ai.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapGoalCheckDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerCourse;

/**
 * Re-arranges the courses of a roadmap into the terms someone asked for: the AI (which proposes) or the student
 * (who decides).
 *
 * <p>The set of courses never changes: it is the system plan's set, so a version can never add a course outside
 * the curriculum or forget one. Only the term of each course moves. Two modes:
 * <ul>
 *   <li>{@link Mode#REPAIR} (AI) — the wanted term is the earliest term a course may take. A course is pushed later
 *       when a prerequisite is not finished before it, when the term is over the credit limit, or when it would
 *       share a term with OJT. Every move is reported, so the result is always a valid plan.</li>
 *   <li>{@link Mode#KEEP} (custom) — the student's placement is kept as is, and every broken rule is reported as
 *       a warning. The student stays in control; the portal shows what to fix.</li>
 * </ul>
 */
final class StudentRoadmapArranger {

    enum Mode { REPAIR, KEEP }

    record Result(List<RoadmapSemesterDto> semesters, RoadmapGoalCheckDto goalCheck, List<String> notes) {}

    private StudentRoadmapArranger() {}

    /**
     * @param base the plan whose courses are arranged (the system plan, or the version a custom plan starts from)
     * @param wantedTerm course id to the wanted term code; a course that is missing keeps its base term
     * @param reasons course id to a new reason, replacing the base reason (AI explanations); may be empty
     * @param termMethods term code to a new study method for that term; may be empty
     * @param open the planner view of the courses, for prerequisites and OJT
     * @param satisfied course ids already passed, exempted or in progress
     */
    static Result arrange(StudyRoadmapDto base, Map<String, String> wantedTerm, Map<String, String> reasons,
            Map<String, String> termMethods, List<PlannerCourse> open, Set<String> satisfied, int maxCredits,
            Mode mode) {
        String start = startTerm(base);
        Map<String, RoadmapCourseDto> courses = new LinkedHashMap<>();
        Map<String, Integer> baseIndex = new HashMap<>();
        Map<String, Integer> baseOrder = new HashMap<>();
        Map<Integer, String> baseMethod = new HashMap<>();
        for (RoadmapSemesterDto semester : base.semesters()) {
            int index = indexOf(start, semester.semesterCode(), 0);
            baseMethod.put(index, semester.studyMethod());
            for (RoadmapCourseDto course : semester.courses()) {
                if (course.courseId() == null || courses.containsKey(course.courseId())) {
                    continue;
                }
                courses.put(course.courseId(), course);
                baseIndex.put(course.courseId(), index);
                baseOrder.put(course.courseId(), baseOrder.size());
            }
        }
        Map<String, PlannerCourse> planner = new HashMap<>();
        for (PlannerCourse course : open) {
            planner.put(course.id(), course);
        }

        Map<String, Integer> wanted = new HashMap<>();
        for (String id : courses.keySet()) {
            String term = wantedTerm == null ? null : StudentRoadmapPlanner.normalizeTerm(wantedTerm.get(id));
            int index = term == null ? baseIndex.get(id) : indexOf(start, term, baseIndex.get(id));
            wanted.put(id, Math.max(0, index));
        }

        List<String> notes = new ArrayList<>();
        Map<String, Integer> placed = mode == Mode.REPAIR
                ? repair(courses, wanted, baseIndex, baseOrder, planner, satisfied, maxCredits, start, notes)
                : keep(courses, wanted, planner, satisfied, maxCredits, start, notes);

        // Build the terms; a term nobody studies in is left out but the codes stay real (a gap term is a break).
        Map<Integer, List<String>> byTerm = new TreeMap<>();
        courses.keySet().stream()
                .sorted(Comparator.comparingInt(baseOrder::get))
                .forEach(id -> byTerm.computeIfAbsent(placed.get(id), k -> new ArrayList<>()).add(id));
        List<RoadmapSemesterDto> semesters = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : byTerm.entrySet()) {
            String term = termAt(start, entry.getKey());
            int number = semesters.size() + 1;
            List<RoadmapCourseDto> dtos = new ArrayList<>();
            int credits = 0;
            for (String id : entry.getValue()) {
                RoadmapCourseDto c = courses.get(id);
                credits += c.credits();
                String reason = reasons != null && reasons.get(id) != null && !reasons.get(id).isBlank()
                        ? reasons.get(id).strip() : c.reason();
                dtos.add(new RoadmapCourseDto(c.courseId(), c.courseCode(), c.courseName(), c.credits(),
                        c.categoryName(), c.isMandatory(), c.priority(), "PLANNED", reason, c.prerequisites(),
                        c.retake(), c.studyTip()));
            }
            String method = termMethods == null ? null : termMethods.get(term);
            if (method == null || method.isBlank()) {
                method = baseMethod.get(entry.getKey());
            }
            String note = credits + " credits · " + dtos.size() + (dtos.size() == 1 ? " course" : " courses")
                    + " · limit " + maxCredits;
            semesters.add(new RoadmapSemesterDto(number, term, title(term), credits,
                    number == 1 ? "PLANNED" : "SUGGESTED", dtos, note, method));
        }
        return new Result(semesters, goalCheck(base.goalCheck(), semesters, notes), notes);
    }

    private static Map<String, Integer> repair(Map<String, RoadmapCourseDto> courses, Map<String, Integer> wanted,
            Map<String, Integer> baseIndex, Map<String, Integer> baseOrder, Map<String, PlannerCourse> planner,
            Set<String> satisfied, int maxCredits, String start, List<String> notes) {
        Map<String, Integer> placed = new HashMap<>();
        Map<Integer, Integer> load = new HashMap<>();
        Map<Integer, Boolean> ojtTerm = new HashMap<>();
        List<String> pending = new ArrayList<>(courses.keySet());
        Comparator<String> order = Comparator.comparingInt((String id) -> wanted.get(id))
                .thenComparingInt(baseIndex::get)
                .thenComparingInt(baseOrder::get);
        while (!pending.isEmpty()) {
            // Next course: one whose planned prerequisites are already placed, earliest wanted term first.
            String next = pending.stream()
                    .filter(id -> prerequisitesPlaced(planner.get(id), courses.keySet(), satisfied, placed))
                    .min(order)
                    .orElseGet(() -> pending.stream().min(order).orElseThrow());
            pending.remove(next);
            RoadmapCourseDto course = courses.get(next);
            boolean ojt = isOjt(planner.get(next), course);
            int term = Math.max(wanted.get(next), earliestAfterPrerequisites(planner.get(next), satisfied, placed));
            while (term < 60) {
                int used = load.getOrDefault(term, 0);
                boolean empty = used == 0;
                boolean fits = empty || used + course.credits() <= maxCredits;
                boolean ojtClash = !empty && (ojt || ojtTerm.getOrDefault(term, false));
                if (fits && !ojtClash) {
                    break;
                }
                term++;
            }
            placed.put(next, term);
            load.merge(term, course.credits(), Integer::sum);
            if (ojt) {
                ojtTerm.put(term, true);
            }
            if (term != wanted.get(next)) {
                notes.add("Moved " + course.courseCode() + " from " + termAt(start, wanted.get(next)) + " to "
                        + termAt(start, term) + " to keep prerequisites, the credit limit and the OJT term valid.");
            }
        }
        return placed;
    }

    private static Map<String, Integer> keep(Map<String, RoadmapCourseDto> courses, Map<String, Integer> wanted,
            Map<String, PlannerCourse> planner, Set<String> satisfied, int maxCredits, String start,
            List<String> notes) {
        Map<String, Integer> placed = new HashMap<>(wanted);
        Map<Integer, Integer> load = new TreeMap<>();
        Map<Integer, List<String>> codesByTerm = new TreeMap<>();
        Map<Integer, Boolean> hasOjt = new HashMap<>();
        for (Map.Entry<String, RoadmapCourseDto> entry : courses.entrySet()) {
            int term = placed.get(entry.getKey());
            load.merge(term, entry.getValue().credits(), Integer::sum);
            codesByTerm.computeIfAbsent(term, k -> new ArrayList<>()).add(entry.getValue().courseCode());
            if (isOjt(planner.get(entry.getKey()), entry.getValue())) {
                hasOjt.put(term, true);
            }
        }
        for (Map.Entry<String, RoadmapCourseDto> entry : courses.entrySet()) {
            PlannerCourse course = planner.get(entry.getKey());
            if (course == null) {
                continue;
            }
            int term = placed.get(entry.getKey());
            for (List<String> group : course.prerequisiteGroups()) {
                boolean met = false;
                String missing = null;
                for (String prerequisite : group) {
                    Integer at = placed.get(prerequisite);
                    if (satisfied.contains(prerequisite) || (at != null && at < term)) {
                        met = true;
                        break;
                    }
                    if (courses.containsKey(prerequisite)) {
                        missing = courses.get(prerequisite).courseCode();
                    }
                }
                if (!met && missing != null) {
                    notes.add(entry.getValue().courseCode() + " in " + termAt(start, term)
                            + " comes before its prerequisite " + missing + " is finished.");
                }
            }
        }
        for (Map.Entry<Integer, Integer> entry : load.entrySet()) {
            if (entry.getValue() > maxCredits) {
                notes.add(termAt(start, entry.getKey()) + " has " + entry.getValue() + " credits, over the limit of "
                        + maxCredits + ".");
            }
            if (hasOjt.getOrDefault(entry.getKey(), false) && codesByTerm.get(entry.getKey()).size() > 1) {
                notes.add(termAt(start, entry.getKey()) + " mixes OJT with other courses; OJT needs a term of its own.");
            }
        }
        return placed;
    }

    private static boolean prerequisitesPlaced(PlannerCourse course, Set<String> planIds, Set<String> satisfied,
            Map<String, Integer> placed) {
        if (course == null) {
            return true;
        }
        for (List<String> group : course.prerequisiteGroups()) {
            boolean met = false;
            boolean reachable = false;
            for (String prerequisite : group) {
                if (satisfied.contains(prerequisite) || placed.containsKey(prerequisite)) {
                    met = true;
                    break;
                }
                if (planIds.contains(prerequisite)) {
                    reachable = true;
                }
            }
            if (!met && reachable) {
                return false;
            }
        }
        return true;
    }

    /** The first term after every prerequisite group is finished (the earliest member of each group counts). */
    private static int earliestAfterPrerequisites(PlannerCourse course, Set<String> satisfied,
            Map<String, Integer> placed) {
        if (course == null) {
            return 0;
        }
        int earliest = 0;
        for (List<String> group : course.prerequisiteGroups()) {
            Integer groupDone = null;
            for (String prerequisite : group) {
                if (satisfied.contains(prerequisite)) {
                    groupDone = -1;
                    break;
                }
                Integer at = placed.get(prerequisite);
                if (at != null && (groupDone == null || at < groupDone)) {
                    groupDone = at;
                }
            }
            if (groupDone != null) {
                earliest = Math.max(earliest, groupDone + 1);
            }
        }
        return earliest;
    }

    private static boolean isOjt(PlannerCourse course, RoadmapCourseDto dto) {
        if (course != null) {
            return StudentRoadmapPlanner.isOjt(course);
        }
        return dto.courseCode() != null && dto.courseCode().toUpperCase(Locale.ROOT).startsWith("OJT");
    }

    private static RoadmapGoalCheckDto goalCheck(RoadmapGoalCheckDto base, List<RoadmapSemesterDto> semesters,
            List<String> notes) {
        String projected = semesters.isEmpty() ? null : semesters.get(semesters.size() - 1).semesterCode();
        if (base == null) {
            return null;
        }
        Boolean onTrack = base.onTrack();
        if (base.targetGraduationTerm() != null && projected != null) {
            Integer distance = StudentRoadmapPlanner.termDistance(projected, base.targetGraduationTerm());
            onTrack = distance != null && distance >= 1;
            if (!onTrack) {
                notes.add("This version graduates in " + projected + ", later than your target "
                        + base.targetGraduationTerm() + ".");
            }
        }
        return new RoadmapGoalCheckDto(base.startTerm(), base.targetGraduationTerm(), projected, onTrack,
                base.remainingCredits(), base.recommendedCreditsPerTerm(), base.currentGpa(), base.targetGpa(),
                base.requiredAverageScore(), base.retakeCount());
    }

    static String startTerm(StudyRoadmapDto base) {
        if (base.goalCheck() != null && StudentRoadmapPlanner.normalizeTerm(base.goalCheck().startTerm()) != null) {
            return StudentRoadmapPlanner.normalizeTerm(base.goalCheck().startTerm());
        }
        for (RoadmapSemesterDto semester : base.semesters()) {
            String term = StudentRoadmapPlanner.normalizeTerm(semester.semesterCode());
            if (term != null) {
                return term;
            }
        }
        return StudentRoadmapPlanner.termOf(java.time.LocalDate.now().getYear(), java.time.LocalDate.now().getMonthValue());
    }

    /** Zero-based index of {@code term} from {@code start}; {@code fallback} when it is not a term code. */
    static int indexOf(String start, String term, int fallback) {
        Integer distance = StudentRoadmapPlanner.termDistance(start, term);
        return distance == null ? fallback : distance - 1;
    }

    static String termAt(String start, int index) {
        String term = start;
        for (int i = 0; i < index; i++) {
            term = StudentRoadmapPlanner.nextTerm(term);
        }
        return term;
    }

    private static String title(String term) {
        String normalized = StudentRoadmapPlanner.normalizeTerm(term);
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
}
