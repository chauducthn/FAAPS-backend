package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

final class TranscriptLookupResolver {
    private static final Pattern FPT_SEMESTER = Pattern.compile("^(SP|SPRING|SU|SUMMER|FA|FALL)\\s*(\\d{2}|20\\d{2})$");
    private static final Pattern PREP_EQUIV = Pattern.compile("^(?:TRS|LUK)([1-6])\\d*$");
    private static final Map<String, String> PREP_ALIASES = Map.ofEntries(
            Map.entry("LUK1", "PEN101"), Map.entry("ENT104", "PEN101"), Map.entry("TRS101", "PEN101"),
            Map.entry("LUK2", "PEN201"), Map.entry("ENT203", "PEN201"), Map.entry("TRS201", "PEN201"),
            Map.entry("LUK3", "PEN301"), Map.entry("ENT303", "PEN301"), Map.entry("TRS301", "PEN301"),
            Map.entry("LUK4", "PEN401"), Map.entry("ENT403", "PEN401"), Map.entry("TRS401", "PEN401"),
            Map.entry("LUK5", "PEN501"), Map.entry("ENT503", "PEN501"), Map.entry("TRS501", "PEN501"),
            Map.entry("LUK6", "PEN601"), Map.entry("TRS601", "PEN601"));

    private TranscriptLookupResolver() {}

    static List<Object[]> resolveCourse(JdbcTemplate jdbc, String upper) {
        List<Object[]> exact = lookupCourse(jdbc, upper);
        if (!exact.isEmpty()) return exact;
        if (PREP_ALIASES.containsKey(upper)) {
            List<Object[]> aliased = lookupCourse(jdbc, PREP_ALIASES.get(upper));
            if (!aliased.isEmpty()) return aliased;
        }
        Matcher pm = PREP_EQUIV.matcher(upper);
        if (pm.matches()) {
            List<Object[]> pen = lookupCourse(jdbc, "PEN" + pm.group(1) + "01");
            if (!pen.isEmpty()) return pen;
        }
        if (upper.startsWith("TMI")) {
            List<Object[]> tmi = lookupCourse(jdbc, "TMI_ELE");
            if (!tmi.isEmpty()) return tmi;
        }
        List<Object[]> slashed = jdbc.query(
                "SELECT id, code FROM courses WHERE UPPER(code) LIKE ? OR UPPER(code) LIKE ? LIMIT 1",
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2)},
                upper + "/%", "%/" + upper);
        if (!slashed.isEmpty()) return slashed;
        return resolveCodeVariant(jdbc, upper);
    }

    /**
     * Resolves a code that the catalog only knows under another delivery variant.
     *
     * <p>FAP exports the code a student actually sat ({@code IoT102t}), while the FLM
     * catalog may list the subject once ({@code IOT102}), and the other way round
     * ({@code SWE201} in FAP, {@code SWE201c} in FLM). A transcript row carries no name,
     * so the base code is the only evidence; it is trusted only when the catalog family
     * of that base code is one subject:
     * <ul>
     *   <li>every member of the family must be a variant of the others (same credits
     *       and names); a family that mixes unrelated courses ({@code ISM301} /
     *       {@code ISM301c}) is ambiguous and stays unresolved;</li>
     *   <li>the plain base code is preferred, otherwise the first variant.</li>
     * </ul>
     */
    static List<Object[]> resolveCodeVariant(JdbcTemplate jdbc, String upper) {
        String base = CourseMatchRules.baseCode(upper);
        if (base == null) return List.of();
        List<CourseMatchRules.CourseRef> family = jdbc.query(
                "SELECT id, code, name, credits FROM courses WHERE UPPER(code) LIKE ? ORDER BY code",
                (rs, n) -> new CourseMatchRules.CourseRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                base + "%").stream()
                .filter(c -> base.equals(CourseMatchRules.baseCode(c.code())))
                .toList();
        if (family.isEmpty()) return List.of();
        CourseMatchRules.CourseRef first = family.get(0);
        for (CourseMatchRules.CourseRef other : family.subList(1, family.size())) {
            if (!CourseMatchRules.isSameCourseVariant(first, other)) return List.of();
        }
        CourseMatchRules.CourseRef chosen = family.stream()
                .filter(c -> c.upperCode().equals(base)).findFirst().orElse(first);
        return List.<Object[]>of(new Object[]{chosen.id(), chosen.code()});
    }

    static String resolveOrCreateSemester(JdbcTemplate jdbc, String rawCode) {
        String existing = idFor(jdbc, "SELECT id FROM semesters WHERE UPPER(code) = ?",
                rawCode.toUpperCase(Locale.ROOT));
        if (existing != null) return existing;
        Matcher m = FPT_SEMESTER.matcher(rawCode.trim().toUpperCase(Locale.ROOT));
        if (!m.matches()) return null;
        String prefix = m.group(1).substring(0, 2);
        int year = m.group(2).length() == 2 ? 2000 + Integer.parseInt(m.group(2)) : Integer.parseInt(m.group(2));
        if (year < 2006 || year > 2040) return null;
        String canonical = prefix + year;
        String found = idFor(jdbc, "SELECT id FROM semesters WHERE UPPER(code) = ?", canonical);
        if (found != null) return found;
        String name = "SP".equals(prefix) ? "Spring " + year
                : "SU".equals(prefix) ? "Summer " + year : "Fall " + year;
        LocalDate start = "SP".equals(prefix) ? LocalDate.of(year, 1, 1)
                : "SU".equals(prefix) ? LocalDate.of(year, 5, 1) : LocalDate.of(year, 9, 1);
        LocalDate end = "SP".equals(prefix) ? LocalDate.of(year, 4, 30)
                : "SU".equals(prefix) ? LocalDate.of(year, 8, 31) : LocalDate.of(year, 12, 31);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current)"
                + " VALUES (?, ?, ?, ?, ?, FALSE)", id, canonical, name, start, end);
        return id;
    }

    private static List<Object[]> lookupCourse(JdbcTemplate jdbc, String upperCode) {
        return jdbc.query("SELECT id, code FROM courses WHERE UPPER(code) = ? LIMIT 1",
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2)}, upperCode);
    }

    private static String idFor(JdbcTemplate jdbc, String sql, String code) {
        return jdbc.query(sql, (rs, row) -> rs.getString(1), code).stream().findFirst().orElse(null);
    }
}
