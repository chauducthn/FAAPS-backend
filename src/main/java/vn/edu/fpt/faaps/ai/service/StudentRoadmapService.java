package vn.edu.fpt.faaps.ai.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.CourseWorkloadService;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.CustomRoadmapTermDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapVersionsDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SaveCustomRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerCourse;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerInput;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerResult;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapStore.StudentAcademicState;

/**
 * Flow 2: builds the study plan a student gets after importing their transcript and setting goals
 * (expected graduation term, career orientation, target GPA).
 */
class StudentRoadmapService {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String DEFAULT_DESC =
            "Optimal curriculum progression based on prerequisites and university guidelines.";

    private final StudentRoadmapStore store;
    private final StudentRoadmapVersionStore versions;
    private final StudentRoadmapAiRefiner refiner;
    private final JdbcTemplate jdbc;

    StudentRoadmapService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    StudentRoadmapService(JdbcTemplate jdbc, StudentRoadmapAiRefiner refiner) {
        this.store = new StudentRoadmapStore(jdbc);
        this.versions = new StudentRoadmapVersionStore(jdbc);
        this.refiner = refiner;
        this.jdbc = jdbc;
    }

    private int defaultMaxCredits() {
        try {
            Integer v = jdbc.queryForObject(
                    "SELECT max_credits_per_semester FROM academic_semester_limits WHERE id = 1", Integer.class);
            return v != null && v > 0 ? v : 21;
        } catch (RuntimeException ignored) {
            return 21;
        }
    }

    StudyRoadmapDto get(UUID studentId) {
        return store.findCurrent(studentId);
    }

    StudyRoadmapDto generate(UUID studentId, GenerateRoadmapDto input) {
        validateInput(input);
        store.updateExpectedGraduationIfProvided(studentId, input);

        StudentAcademicState state = store.academicState(studentId);
        List<PlannerCourse> open = store.openCourses(state);
        int maxCredits = maxCredits(input);

        Set<String> satisfied = new java.util.HashSet<>(state.satisfiedCourseIds());
        satisfied.addAll(state.inProgressCourseIds());
        String startTerm = startTerm(state.latestRecordTerm(), LocalDate.now());
        String target = targetTerm(studentId, input);
        PlannerResult result = StudentRoadmapPlanner.plan(new PlannerInput(open, satisfied, startTerm, target,
                maxCredits, input == null ? null : input.orientation(), state.currentGpa(),
                input == null ? null : input.targetGpa(), state.passedCredits()));

        List<String> warnings = new ArrayList<>(result.warnings());
        if (!state.inProgressCourseIds().isEmpty()) {
            int count = state.inProgressCourseIds().size();
            warnings.add(0, count + (count == 1 ? " course is" : " courses are")
                    + " still in progress and treated as passed. Import your transcript again once grades are out.");
        }
        if (state.curriculumId() == null) {
            warnings.add(0, "Choose your curriculum first; the plan is built from its courses.");
        } else if (open.isEmpty()) {
            warnings.add(0, "You have no courses left in your curriculum. Check graduation requirements with "
                    + "your advisor.");
        }

        int totalCredits = result.semesters().stream().mapToInt(semester -> semester.totalCredits()).sum();
        String planId = UUID.randomUUID().toString();
        StudyRoadmapDto roadmap = new StudyRoadmapDto(planId, studentId.toString(), "Study Roadmap",
                buildDescription(input, result.goalCheck().projectedGraduationTerm()),
                LocalDateTime.now().format(ISO_DT), "RULE_BASED", null, result.semesters().size(), totalCredits,
                result.semesters(), result.goalCheck(), warnings, result.studyMethods());
        putVersion(studentId, StudentRoadmapVersionStore.SYSTEM, roadmap, null, false);
        return roadmap;
    }

    private int maxCredits(GenerateRoadmapDto input) {
        int configuredLimit = defaultMaxCredits();
        return input != null && input.maxCreditsPerSemester() != null && input.maxCreditsPerSemester() > 0
                ? Math.min(input.maxCreditsPerSemester(), configuredLimit) : configuredLimit;
    }

    // ---- versions: SYSTEM (rule-based), AI (refinement of SYSTEM), CUSTOM (the student's own edit) ------------

    /** Every version the student has, and the one they follow. */
    RoadmapVersionsDto versions(UUID studentId) {
        List<StudentRoadmapVersionStore.Version> all = versions.all(studentId);
        if (all.isEmpty()) {
            // A roadmap generated before versions existed becomes the system version.
            StudyRoadmapDto legacy = store.findCurrent(studentId);
            if (legacy != null) {
                versions.put(studentId, StudentRoadmapVersionStore.SYSTEM, legacy, null);
                versions.activate(studentId, StudentRoadmapVersionStore.SYSTEM);
                all = versions.all(studentId);
            }
        }
        String active = null;
        StudyRoadmapDto system = null;
        StudyRoadmapDto ai = null;
        StudyRoadmapDto custom = null;
        String customBasedOn = null;
        for (StudentRoadmapVersionStore.Version v : all) {
            if (v.active()) {
                active = v.kind();
            }
            switch (v.kind()) {
                case StudentRoadmapVersionStore.SYSTEM -> system = v.roadmap();
                case StudentRoadmapVersionStore.AI -> ai = v.roadmap();
                case StudentRoadmapVersionStore.CUSTOM -> {
                    custom = v.roadmap();
                    customBasedOn = v.basedOn();
                }
                default -> { }
            }
        }
        return new RoadmapVersionsDto(active, system, ai, custom, customBasedOn);
    }

    /**
     * Builds the AI version: the system plan is rebuilt for the current goals, the AI proposes a better
     * arrangement, and the arranger repairs anything that breaks a rule.
     */
    StudyRoadmapDto generateAi(UUID studentId, GenerateRoadmapDto input) {
        if (refiner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The AI planner is not configured.");
        }
        StudyRoadmapDto system = generate(studentId, input);
        if (system.semesters().isEmpty()) {
            throw new IllegalArgumentException("There are no courses left to plan, so there is nothing for the AI "
                    + "to improve.");
        }
        StudentAcademicState state = store.academicState(studentId);
        List<PlannerCourse> open = store.openCourses(state);
        Set<String> satisfied = new HashSet<>(state.satisfiedCourseIds());
        satisfied.addAll(state.inProgressCourseIds());
        int maxCredits = maxCredits(input);

        String start = StudentRoadmapArranger.startTerm(system);
        String target = system.goalCheck() == null ? null : system.goalCheck().targetGraduationTerm();
        String last = system.semesters().get(system.semesters().size() - 1).semesterCode();
        Integer toTarget = target == null ? null : StudentRoadmapPlanner.termDistance(start, target);
        Integer toLast = StudentRoadmapPlanner.termDistance(start, last);
        int span = Math.max(toTarget == null ? 0 : toTarget, toLast == null ? 1 : toLast) + 1;
        List<String> allowedTerms = new ArrayList<>();
        for (int i = 0; i < Math.min(span, 30); i++) {
            allowedTerms.add(StudentRoadmapArranger.termAt(start, i));
        }

        List<String> ids = system.semesters().stream().flatMap(t -> t.courses().stream())
                .map(RoadmapCourseDto::courseId).toList();
        Map<String, String> workload = new HashMap<>();
        try {
            new CourseWorkloadService(jdbc).evaluateByCourseIds(ids)
                    .forEach((id, w) -> workload.put(id, w == null ? "UNKNOWN" : w.level()));
        } catch (RuntimeException ignored) { /* workload is a hint only */ }

        StudentRoadmapAiRefiner.Proposal proposal = refiner.propose(system,
                new StudentRoadmapAiRefiner.Goals(input == null ? null : input.orientation(),
                        input == null ? null : input.goal(), input == null ? null : input.targetGpa(),
                        state.currentGpa(), target),
                workload, maxCredits, allowedTerms);
        StudentRoadmapArranger.Result arranged = StudentRoadmapArranger.arrange(system, proposal.termByCourseId(),
                proposal.reasonByCourseId(), proposal.methodByTerm(), open, satisfied, maxCredits,
                StudentRoadmapArranger.Mode.REPAIR);

        List<String> warnings = new ArrayList<>();
        // Warnings about the system plan's own timeline do not apply to this arrangement.
        system.warnings().stream().filter(w -> !w.startsWith("At up to")).forEach(warnings::add);
        warnings.addAll(proposal.ignored());
        warnings.addAll(arranged.notes());
        StudyRoadmapDto roadmap = new StudyRoadmapDto(UUID.randomUUID().toString(), studentId.toString(),
                "AI Study Roadmap",
                proposal.summary() == null || proposal.summary().isBlank()
                        ? "AI-refined version of the system plan." : proposal.summary(),
                LocalDateTime.now().format(ISO_DT), proposal.modelName(), null, arranged.semesters().size(),
                arranged.semesters().stream().mapToInt(RoadmapSemesterDto::totalCredits).sum(),
                arranged.semesters(), arranged.goalCheck(), warnings,
                proposal.advice().isEmpty() ? system.studyMethods() : proposal.advice());
        putVersion(studentId, StudentRoadmapVersionStore.AI, roadmap, null, false);
        return roadmap;
    }

    /**
     * Saves the student's own arrangement. The placement is kept as the student made it; every broken rule is
     * returned as a warning. The custom version becomes the one the student follows.
     */
    StudyRoadmapDto saveCustom(UUID studentId, SaveCustomRoadmapDto input) {
        if (input == null) {
            throw new IllegalArgumentException("A custom roadmap is required");
        }
        String basedOn = StudentRoadmapVersionStore.normalizeKind(input.basedOn());
        StudentRoadmapVersionStore.Version base = versions.find(studentId, basedOn);
        if (base == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generate the " + basedOn.toLowerCase(Locale.ROOT)
                    + " roadmap before customizing it.");
        }
        String origin = StudentRoadmapVersionStore.CUSTOM.equals(basedOn)
                ? (base.basedOn() == null ? StudentRoadmapVersionStore.SYSTEM : base.basedOn()) : basedOn;
        Map<String, String> wanted = new HashMap<>();
        if (input.terms() != null) {
            for (CustomRoadmapTermDto term : input.terms()) {
                if (term == null || term.courseIds() == null
                        || StudentRoadmapPlanner.normalizeTerm(term.semesterCode()) == null) {
                    continue;
                }
                term.courseIds().forEach(id -> wanted.put(id, term.semesterCode()));
            }
        }
        StudentAcademicState state = store.academicState(studentId);
        Set<String> satisfied = new HashSet<>(state.satisfiedCourseIds());
        satisfied.addAll(state.inProgressCourseIds());
        StudentRoadmapArranger.Result arranged = StudentRoadmapArranger.arrange(base.roadmap(), wanted, Map.of(),
                Map.of(), store.openCourses(state), satisfied, defaultMaxCredits(), StudentRoadmapArranger.Mode.KEEP);
        StudyRoadmapDto roadmap = new StudyRoadmapDto(UUID.randomUUID().toString(), studentId.toString(),
                "My Study Roadmap", "Your own arrangement, edited from the " + origin.toLowerCase(Locale.ROOT)
                + " version.", LocalDateTime.now().format(ISO_DT), "CUSTOM", null, arranged.semesters().size(),
                arranged.semesters().stream().mapToInt(RoadmapSemesterDto::totalCredits).sum(),
                arranged.semesters(), arranged.goalCheck(), arranged.notes(), base.roadmap().studyMethods());
        putVersion(studentId, StudentRoadmapVersionStore.CUSTOM, roadmap, origin, true);
        return roadmap;
    }

    /** Makes {@code kind} the version the student follows. */
    RoadmapVersionsDto selectVersion(UUID studentId, String kind) {
        String normalized = StudentRoadmapVersionStore.normalizeKind(kind);
        StudentRoadmapVersionStore.Version version = versions.find(studentId, normalized);
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This roadmap version does not exist yet.");
        }
        versions.activate(studentId, normalized);
        store.save(studentId, withNewId(version.roadmap()));
        return versions(studentId);
    }

    /**
     * Stores a version. The version the student follows is also written as their current study plan, so every
     * screen that reads the current plan (advisor review, portal, AI chat) sees the version they chose.
     */
    private void putVersion(UUID studentId, String kind, StudyRoadmapDto roadmap, String basedOn, boolean activate) {
        versions.put(studentId, kind, roadmap, basedOn);
        String active = versions.activeKind(studentId);
        if (activate || active == null || active.equals(kind)) {
            versions.activate(studentId, kind);
            store.save(studentId, roadmap);
        }
    }

    private static StudyRoadmapDto withNewId(StudyRoadmapDto r) {
        return new StudyRoadmapDto(UUID.randomUUID().toString(), r.studentId(), r.title(), r.summary(),
                r.generatedAt(), r.modelName(), r.confidenceScore(), r.totalSemesters(), r.totalCredits(),
                r.semesters(), r.goalCheck(), r.warnings(), r.studyMethods());
    }

    /**
     * The first term the plan can fill: the term after the latest one in the transcript, and never a term that
     * is already over or about to end.
     */
    static String startTerm(String latestRecordTerm, LocalDate today) {
        String current = StudentRoadmapPlanner.termOf(today.getYear(), today.getMonthValue());
        // Registration for the current term closes in its first weeks; after that plan from the next one.
        boolean currentStillOpen = switch (today.getMonthValue()) {
            case 1, 5, 9 -> today.getDayOfMonth() <= 21;
            default -> false;
        };
        String earliest = currentStillOpen ? current : StudentRoadmapPlanner.nextTerm(current);
        if (latestRecordTerm == null) {
            return earliest;
        }
        String afterRecords = StudentRoadmapPlanner.nextTerm(latestRecordTerm);
        Integer distance = StudentRoadmapPlanner.termDistance(earliest, afterRecords);
        return distance != null && distance > 1 ? afterRecords : earliest;
    }

    private String targetTerm(UUID studentId, GenerateRoadmapDto input) {
        if (input != null && input.expectedGraduationSemesterId() != null
                && !input.expectedGraduationSemesterId().isBlank()) {
            String code = StudentRoadmapPlanner.normalizeTerm(
                    store.resolveSemesterCode(input.expectedGraduationSemesterId().strip()));
            if (code != null) {
                return code;
            }
        }
        return StudentRoadmapPlanner.normalizeTerm(store.savedGraduationTerm(studentId));
    }

    private void validateInput(GenerateRoadmapDto input) {
        if (input == null) {
            return;
        }
        if (input.maxCreditsPerSemester() != null
                && (input.maxCreditsPerSemester() < 1 || input.maxCreditsPerSemester() > 30)) {
            throw new IllegalArgumentException("maxCreditsPerSemester must be between 1 and 30");
        }
        if (input.targetGpa() != null && (input.targetGpa() < 0.0 || input.targetGpa() > 10.0)) {
            throw new IllegalArgumentException("targetGpa must be between 0.00 and 10.00");
        }
        if (input.orientation() != null && !input.orientation().isBlank()) {
            String orient = input.orientation().strip().toUpperCase();
            if (!Set.of("ACADEMIC", "JOB", "RESEARCH", "ENTREPRENEURIAL").contains(orient)) {
                throw new IllegalArgumentException("orientation must be ACADEMIC, JOB, RESEARCH, or ENTREPRENEURIAL");
            }
        }
    }

    private String buildDescription(GenerateRoadmapDto input, String projectedTerm) {
        List<String> parts = new ArrayList<>();
        if (input != null) {
            if (input.goal() != null && !input.goal().isBlank()) {
                parts.add("Customized for goal: " + input.goal().strip());
            }
            if (input.orientation() != null && !input.orientation().isBlank()) {
                parts.add("Orientation: " + input.orientation().strip().toUpperCase(Locale.ROOT));
            }
            if (input.targetGpa() != null) {
                parts.add(String.format(Locale.ROOT, "Target GPA: %.2f", input.targetGpa()));
            }
            if (input.expectedGraduationSemesterId() != null && !input.expectedGraduationSemesterId().isBlank()) {
                String expId = input.expectedGraduationSemesterId().strip();
                String semCode = store.resolveSemesterCode(expId);
                parts.add("Target graduation: " + (semCode != null ? semCode : expId));
            }
        }
        if (projectedTerm != null) {
            parts.add("Projected graduation: " + projectedTerm);
        }
        return parts.isEmpty() ? DEFAULT_DESC : String.join(" | ", parts);
    }
}
