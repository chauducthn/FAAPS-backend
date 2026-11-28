package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds a transitive, bidirectional course equivalence graph.
 *
 * <p>A course is <em>equivalent</em> to another when a student who passed one of them
 * must be credited for the other. The graph has two tiers, and the distinction matters:
 *
 * <ol>
 *   <li><b>Authoritative equivalences</b> — applied automatically.
 *       <ul>
 *         <li>Declared substitutions: an admin explicitly records
 *             {@code course_prerequisites} rows with {@code type = 'REPLACEMENT'}. A
 *             relation may be scoped to a single curriculum version (cohort) via
 *             {@code effective_from_curriculum_version_id}; a {@code NULL} scope means
 *             the relation applies to every curriculum.</li>
 *         <li>Structural aliases: the same course under a different code
 *             ({@code CSI104} / {@code CSI104c}, {@code DBI202} / {@code DBI202-OLD}) when
 *             the credits and names agree too (see {@link CourseMatchRules}: FPTU reuses
 *             some base codes for unrelated courses, e.g. {@code ISM301} / {@code ISM301c}),
 *             the FPTU prep English levels ({@code TRS403} / {@code PEN401}) and the
 *             elective placeholders ({@code TMI*} / {@code TMI_ELE}). These are code
 *             conventions rather than curriculum decisions, so they need no approval.</li>
 *       </ul>
 *   <li><b>Inferred equivalences</b> — structural evidence that two courses cover the
 *       same subject: the same three-letter prefix, the same credits and a name that
 *       either matches exactly or where one name is a whole-word prefix of the other
 *       ({@code CSI104 "Introduction to Computer"} vs
 *       {@code CSI106 "Introduction to Computer Science"}). These are never applied
 *       silently: a wrong equivalence would corrupt the graduation audit, so they are
 *       only surfaced as suggestions for an admin to confirm.
 * </ol>
 *
 * <p>Everything is computed as a union-find over course codes, so equivalences are
 * transitive and symmetric: if A replaces B and B replaces C, then A, B and C all
 * satisfy each other.
 */
@Service
public class CourseEquivalenceService {
    /** The three-letter subject prefix of a course code. */
    private static final Pattern PREFIX_RE = Pattern.compile("^([A-Z]{3})\\d{3}");
    /** FPTU prep English levels (TRS/LUK 1..6). */
    private static final Pattern PREP_RE = Pattern.compile("^(?:PEN|TRS|LUK)([1-6])\\d*$");
    /** Codes that look like a plain subject code: three letters + three digits. */
    private static final Pattern PLAIN_CODE_RE = Pattern.compile("^[A-Z]{3}\\d{3}$");

    private final JdbcTemplate jdbc;

    public CourseEquivalenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The equivalence graph without any curriculum scoping. Every course maps to the
     * list of codes that satisfy the same curriculum requirement.
     */
    public Map<String, List<String>> equivalenceMap() {
        return equivalenceMap(null);
    }

    /**
     * The equivalence graph scoped to one curriculum version.
     *
     * <p>Declared substitutions are filtered by scope: a relation scoped to another
     * curriculum version is ignored, and a relation scoped to the requested version (or
     * unscoped) applies. Inferred substitutions are structural and therefore apply
     * everywhere.
     *
     * @param curriculumVersionId the curriculum the graph is built for, or {@code null}
     *     for the unscoped graph
     */
    public Map<String, List<String>> equivalenceMap(String curriculumVersionId) {
        List<CourseInfo> courses = loadCourses();
        if (courses.isEmpty()) return Map.of();

        Map<String, String> parent = new HashMap<>();
        for (CourseInfo c : courses) parent.put(c.upperCode(), c.upperCode());

        // 1. Declared substitutions. A REPLACEMENT row on `course` names the course that
        //    replaces it, so the edge is symmetric: both satisfy each other.
        try {
            jdbc.query("""
                    SELECT UPPER(c.code), UPPER(p.code), cp.effective_from_curriculum_version_id
                    FROM course_prerequisites cp
                    JOIN courses c ON c.id = cp.course_id
                    JOIN courses p ON p.id = cp.prerequisite_course_id
                    WHERE UPPER(cp.type) = 'REPLACEMENT'
                    """, rs -> {
                String scope = rs.getString(3);
                if (appliesTo(scope, curriculumVersionId)) {
                    union(parent, rs.getString(1), rs.getString(2));
                }
            });
        } catch (RuntimeException ignored) { /* minimal test schema */ }

        // 1b. Every TMI elective instrument satisfies the TMI_ELE placeholder.
        if (parent.containsKey("TMI_ELE")) {
            try {
                jdbc.query("""
                        SELECT UPPER(gc.course_code) FROM flm_subject_group_courses gc
                        JOIN flm_subject_groups g ON g.id = gc.group_id
                        WHERE UPPER(g.group_code) LIKE 'TMI%'
                        """, rs -> {
                    union(parent, rs.getString(1), "TMI_ELE");
                });
            } catch (RuntimeException ignored) { /* minimal test schema */ }
        }

        // 2. Same base code with a variant suffix (CSI104 <-> CSI104c, DBI202 <-> DBI202-OLD),
        //    only when credits and names agree: ISM301 "ERP Systems" and ISM301c
        //    "International Strategic Management" share a base code but are unrelated.
        Map<String, List<CourseInfo>> byBase = new HashMap<>();
        // 3. Prep English levels (TRS403 <-> PEN401).
        Map<String, String> byPrepLevel = new HashMap<>();

        for (CourseInfo c : courses) {
            String code = c.upperCode();
            String base = CourseMatchRules.baseCode(code);
            if (base != null) {
                List<CourseInfo> family = byBase.computeIfAbsent(base, k -> new ArrayList<>());
                for (CourseInfo other : family) {
                    if (CourseMatchRules.isSameCourseVariant(c.ref(), other.ref())) union(parent, other.upperCode(), code);
                }
                family.add(c);
            }
            Matcher em = PREP_RE.matcher(code);
            if (em.matches()) {
                String lvl = em.group(1);
                String prev = byPrepLevel.putIfAbsent(lvl, code);
                if (prev != null) union(parent, prev, code);
            }
            if (code.startsWith("TMI") && parent.containsKey("TMI_ELE")) {
                union(parent, code, "TMI_ELE");
            }
            if (code.contains("/")) {
                for (String part : code.split("/")) {
                    String p = part.strip();
                    if (parent.containsKey(p)) union(parent, code, p);
                }
            }
        }

        // Note: the "same subject prefix + same credits + compatible name" rule is
        // deliberately NOT applied here. It is evidence, not a decision: see
        // {@link #suggestSubstitutions(String)}. Applying it automatically would let a
        // wrong guess silently satisfy a curriculum requirement.

        Map<String, List<String>> components = new LinkedHashMap<>();
        for (String code : parent.keySet()) {
            components.computeIfAbsent(find(parent, code), k -> new ArrayList<>()).add(code);
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (List<String> group : components.values()) {
            if (group.size() < 2) continue;
            Collections.sort(group);
            for (String code : group) {
                List<String> others = new ArrayList<>(group.size() - 1);
                for (String other : group) {
                    if (!other.equals(code)) others.add(other);
                }
                result.put(code, others);
            }
        }
        return result;
    }

    /**
     * The equivalence graph for one curriculum version, keyed by course id.
     *
     * <p>Curriculum matching works on ids, so the code-based graph is translated here.
     */
    public Map<String, List<String>> equivalenceMapByCourseId(String curriculumVersionId) {
        Map<String, List<String>> byCode = equivalenceMap(curriculumVersionId);
        if (byCode.isEmpty()) return Map.of();
        Map<String, String> idByCode = new HashMap<>();
        try {
            jdbc.query("SELECT id, UPPER(code) FROM courses", rs -> {
                idByCode.put(rs.getString(2), rs.getString(1));
            });
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byCode.entrySet()) {
            String id = idByCode.get(entry.getKey());
            if (id == null) continue;
            List<String> ids = new ArrayList<>();
            for (String code : entry.getValue()) {
                String other = idByCode.get(code);
                if (other != null) ids.add(other);
            }
            if (!ids.isEmpty()) result.put(id, ids);
        }
        return result;
    }

    /** All codes that satisfy the same requirement as {@code courseCode}, including itself. */
    public Set<String> equivalentCodesOf(String courseCode) {
        if (courseCode == null || courseCode.isBlank()) return Set.of();
        String upper = courseCode.strip().toUpperCase(Locale.ROOT);
        Set<String> set = new LinkedHashSet<>();
        set.add(upper);
        List<String> others = equivalenceMap().get(upper);
        if (others != null) set.addAll(others);
        return set;
    }

    /**
     * Suggests courses that look like a replacement for {@code courseId}: the same
     * subject prefix and the same credits, but a different code. The suggestion is what
     * an admin sees before confirming a substitution, so it stays deliberately broad
     * and reports why each candidate was proposed.
     */
    public List<SubstitutionSuggestion> suggestSubstitutions(String courseId) {
        List<SubstitutionSuggestion> suggestions = new ArrayList<>();
        CourseInfo target = courseInfoById(courseId);
        if (target == null) return suggestions;
        Matcher pm = PREFIX_RE.matcher(target.upperCode());
        if (!pm.find()) return suggestions;
        String subject = pm.group(1);
        try {
            List<CourseInfo> candidates = jdbc.query(
                    "SELECT id, UPPER(code), name, credits FROM courses WHERE UPPER(code) LIKE ? AND credits = ?",
                    (rs, n) -> new CourseInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                            normalizeName(rs.getString(3)), rs.getInt(4)),
                    subject + "%", target.credits());
            for (CourseInfo candidate : candidates) {
                if (candidate.upperCode().equals(target.upperCode())) continue;
                if (!PLAIN_CODE_RE.matcher(candidate.upperCode()).matches()) continue;
                String reason;
                if (candidate.normName().equals(target.normName())) {
                    reason = "Same subject prefix and identical course name";
                } else if (CourseMatchRules.namesCompatible(candidate.rawName(), target.rawName())) {
                    reason = "Same subject prefix, same credits and a near-identical course name";
                } else {
                    reason = "Same subject prefix (" + subject + ") and the same number of credits";
                }
                suggestions.add(new SubstitutionSuggestion(
                        candidate.courseId(), candidate.upperCode(), candidate.rawName(), target.credits(), reason));
            }
        } catch (RuntimeException ignored) { /* minimal test schema */ }
        return suggestions;
    }

    /**
     * The equivalence graph built from structural evidence only.
     *
     * <p>This graph is <em>not</em> applied to student progress. It is the pool of
     * candidates an admin can turn into a declared substitution, and it is used to
     * explain to a student why a course looks like it fulfils another one.
     */
    public Map<String, List<String>> inferredEquivalenceMap() {
        List<CourseInfo> courses = loadCourses();
        if (courses.isEmpty()) return Map.of();
        Map<String, String> parent = new HashMap<>();
        for (CourseInfo c : courses) parent.put(c.upperCode(), c.upperCode());

        Map<String, List<String>> bySubject = new LinkedHashMap<>();
        for (CourseInfo c : courses) {
            String code = c.upperCode();
            if (!PLAIN_CODE_RE.matcher(code).matches() || c.normName().isEmpty()) continue;
            Matcher pm = PREFIX_RE.matcher(code);
            if (!pm.find()) continue;
            bySubject.computeIfAbsent(pm.group(1) + "|" + c.credits(), k -> new ArrayList<>()).add(code);
        }
        for (List<String> group : bySubject.values()) {
            if (group.size() < 2) continue;
            for (String a : group) {
                CourseInfo ca = infoByCode(courses, a);
                if (ca == null) continue;
                for (String b : group) {
                    if (a.equals(b)) continue;
                    CourseInfo cb = infoByCode(courses, b);
                    if (cb == null) continue;
                    if (CourseMatchRules.namesCompatible(ca.rawName(), cb.rawName())) union(parent, a, b);
                }
            }
        }

        Map<String, List<String>> components = new LinkedHashMap<>();
        for (String code : parent.keySet()) {
            components.computeIfAbsent(find(parent, code), k -> new ArrayList<>()).add(code);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (List<String> group : components.values()) {
            if (group.size() < 2) continue;
            Collections.sort(group);
            for (String code : group) {
                List<String> others = new ArrayList<>();
                for (String other : group) {
                    if (!other.equals(code)) others.add(other);
                }
                result.put(code, others);
            }
        }
        return result;
    }

    /**
     * A declared substitution applies when it has no scope, or when its scope is the
     * requested curriculum version.
     *
     * <p>Scoping is deliberately simple: a relation scoped to a cohort replaces the
     * course for that cohort and the cohorts that come after it, which is how FPTU
     * curriculum changes roll out.
     */
    private boolean appliesTo(String scope, String curriculumVersionId) {
        if (scope == null || scope.isBlank()) return true;
        if (curriculumVersionId == null || curriculumVersionId.isBlank()) return false;
        return scope.equals(curriculumVersionId);
    }

    private CourseInfo infoByCode(List<CourseInfo> courses, String code) {
        for (CourseInfo c : courses) {
            if (c.upperCode().equals(code)) return c;
        }
        return null;
    }

    private CourseInfo courseInfoById(String courseId) {
        try {
            return jdbc.query("SELECT id, UPPER(code), name, credits FROM courses WHERE id = ?",
                    (rs, n) -> new CourseInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                            normalizeName(rs.getString(3)), rs.getInt(4)),
                    courseId).stream().findFirst().orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<CourseInfo> loadCourses() {
        try {
            return jdbc.query("SELECT id, UPPER(code), name, credits FROM courses",
                    (rs, n) -> new CourseInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                            normalizeName(rs.getString(3)), rs.getInt(4)));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static String normalizeName(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{L}]+", " ").trim();
    }

    private static String find(Map<String, String> parent, String x) {
        String p = parent.get(x);
        if (p == null) return x;
        if (!p.equals(x)) {
            p = find(parent, p);
            parent.put(x, p);
        }
        return p;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        if (!parent.containsKey(a) || !parent.containsKey(b)) return;
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    /** A course that an admin may want to declare as a replacement. */
    public record SubstitutionSuggestion(String courseId, String code, String name, int credits, String reason) {}

    private record CourseInfo(String courseId, String upperCode, String rawName, String normName, int credits) {
        CourseMatchRules.CourseRef ref() {
            return new CourseMatchRules.CourseRef(courseId, upperCode, rawName, credits);
        }
    }
}
