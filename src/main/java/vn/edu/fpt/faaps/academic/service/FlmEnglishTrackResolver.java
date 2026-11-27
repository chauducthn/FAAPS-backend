package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService.CurriculumCourse;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.NamedOption;

final class FlmEnglishTrackResolver {
    private static final Pattern LEVEL_CODE = Pattern.compile("^([A-Z]+)(\\d+)$");

    private FlmEnglishTrackResolver() {}

    static List<CurriculumCourse> resolveChain(JdbcTemplate jdbc, String startCode,
            CurriculumCourse placeholder, CurriculumCourse fallback) {
        String upper = startCode.strip().toUpperCase(Locale.ROOT);
        Matcher m = LEVEL_CODE.matcher(upper);
        if (m.matches()) {
            int startNum = Integer.parseInt(m.group(2));
            int startLvl = startNum >= 100 ? startNum / 100 : startNum;
            boolean lukTrack = startLvl <= 3;
            try {
                List<CurriculumCourse> chain = jdbc.query(
                        "SELECT id, code, name, credits FROM courses WHERE status = 'ACTIVE'"
                                + " AND UPPER(code) REGEXP ? ORDER BY code ASC",
                        (rs, n) -> new CurriculumCourse(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4), placeholder.semesterNumber(), false, "English Prep"),
                        "^" + m.group(1) + "[0-9]+$");
                List<CurriculumCourse> levels = chain.stream().filter(c -> {
                    Matcher cm = LEVEL_CODE.matcher(c.code().toUpperCase(Locale.ROOT));
                    return cm.matches() && Integer.parseInt(cm.group(2)) >= startNum;
                }).map(c -> mapTrack(c, lukTrack)).toList();
                if (!levels.isEmpty()) return levels;
            } catch (RuntimeException ignored) { /* fallback below */ }
        }
        return List.of(fallback);
    }

    static List<NamedOption> resolveOptions(JdbcTemplate jdbc) {
        try {
            List<NamedOption> fromDb = jdbc.query(
                    "SELECT code, name FROM courses WHERE status = 'ACTIVE'"
                            + " AND UPPER(code) REGEXP '^(PEN|TRS|LUK)[0-9]' ORDER BY code ASC",
                    (rs, n) -> new NamedOption(rs.getString(1), rs.getString(1) + " — " + rs.getString(2)));
            if (fromDb.isEmpty()) return List.of();
            List<NamedOption> out = new ArrayList<>(fromDb);
            out.add(new NamedOption("EXEMPT", "Exempt (IELTS >= 6.0 / Equivalent)"));
            return out;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static CurriculumCourse mapTrack(CurriculumCourse c, boolean lukTrack) {
        Matcher cm = LEVEL_CODE.matcher(c.code().toUpperCase(Locale.ROOT));
        if (!cm.matches() || !"PEN".equals(cm.group(1))) return c;
        int num = Integer.parseInt(cm.group(2));
        int lvl = num >= 100 ? num / 100 : num;
        if (lvl < 1 || lvl > 6) return c;
        String trackCode = (lukTrack && lvl <= 5) ? ("LUK" + lvl) : ("TRS" + (lvl * 100 + 1));
        String trackGroup = (lukTrack && lvl <= 5) ? "LUK" : "TRS";
        return new CurriculumCourse(
                c.courseId(), trackCode, c.name(), c.credits(), c.semesterNumber(), false, trackGroup);
    }
}
