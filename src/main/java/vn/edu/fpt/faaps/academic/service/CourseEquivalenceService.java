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
 * Builds a transitive, bidirectional course equivalence graph from:
 * 1. Explicit DB REPLACEMENT relations in course_prerequisites.
 * 2. Version/suffix variants (CSI104 <-> CSI104c, DBI202 <-> DBI202-OLD).
 * 3. Same 3-letter prefix + identical normalized name + same credits (CSI104 <-> CSI106).
 * 4. FPTU prep/elective groups (TRS/LUK <-> PEN, TMI* <-> TMI_ELE, slash-combined codes).
 */
@Service
public class CourseEquivalenceService {
    private static final Pattern SUFFIX_RE = Pattern.compile("^([A-Z]{2,4}\\d{3})[A-Z]?(?:[-_](?:OLD|NEW|V\\d+))?\\*?$");
    private static final Pattern PREFIX_RE = Pattern.compile("^([A-Z]{3})\\d{3}");
    private static final Pattern PREP_RE = Pattern.compile("^(?:PEN|TRS|LUK)([1-6])\\d*$");
    private final JdbcTemplate jdbc;

    public CourseEquivalenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, List<String>> equivalenceMap() {
        List<CourseInfo> courses = loadCourses();
        if (courses.isEmpty()) return Map.of();

        Map<String, String> parent = new HashMap<>();
        for (CourseInfo c : courses) parent.put(c.upperCode(), c.upperCode());

        // 1. Explicit DB REPLACEMENT edges
        try {
            jdbc.query("""
                    SELECT UPPER(c.code), UPPER(p.code)
                    FROM course_prerequisites cp
                    JOIN courses c ON c.id = cp.course_id
                    JOIN courses p ON p.id = cp.prerequisite_course_id
                    WHERE UPPER(cp.type) = 'REPLACEMENT'
                    """, rs -> {
                union(parent, rs.getString(1), rs.getString(2));
            });
        } catch (RuntimeException ignored) { /* minimal test schema */ }

        // 1b. TMI elective instruments all satisfy TMI_ELE
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

        // 2. Same base code with suffix variant (e.g. CSI104 <-> CSI104c, DBI202 <-> DBI202-OLD)
        Map<String, String> byBase = new HashMap<>();
        // 3. Same 3-letter prefix + normalized name + credits (e.g. CSI104 <-> CSI106)
        Map<String, String> byPrefixNameCredits = new HashMap<>();
        // 4. Prep English levels (TRS403 <-> PEN401)
        Map<String, String> byPrepLevel = new HashMap<>();

        for (CourseInfo c : courses) {
            String code = c.upperCode();
            Matcher sm = SUFFIX_RE.matcher(code);
            if (sm.matches()) {
                String base = sm.group(1);
                String prev = byBase.putIfAbsent(base, code);
                if (prev != null) union(parent, prev, code);
            }
            Matcher pm = PREFIX_RE.matcher(code);
            if (pm.find() && !c.normName().isEmpty()) {
                String key = pm.group(1) + "|" + c.normName() + "|" + c.credits();
                String prev = byPrefixNameCredits.putIfAbsent(key, code);
                if (prev != null) union(parent, prev, code);
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

    public Set<String> equivalentCodesOf(String courseCode) {
        if (courseCode == null || courseCode.isBlank()) return Set.of();
        String upper = courseCode.strip().toUpperCase(Locale.ROOT);
        Set<String> set = new LinkedHashSet<>();
        set.add(upper);
        List<String> others = equivalenceMap().get(upper);
        if (others != null) set.addAll(others);
        return set;
    }

    private List<CourseInfo> loadCourses() {
        try {
            return jdbc.query("SELECT UPPER(code), name, credits FROM courses",
                    (rs, n) -> new CourseInfo(rs.getString(1), normalizeName(rs.getString(2)), rs.getInt(3)));
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

    private record CourseInfo(String upperCode, String normName, int credits) {}
}
