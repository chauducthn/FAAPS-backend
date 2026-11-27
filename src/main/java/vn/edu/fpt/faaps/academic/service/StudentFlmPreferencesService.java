package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService.CurriculumCourse;

/** Persists FLM personalization choices and resolves placeholders dynamically from imported FLM groups. */
@Service
public class StudentFlmPreferencesService {
    private static final Pattern COMBO_SLOT = Pattern.compile("COM\\*?(\\d)", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;
    private final FlmSubjectGroupService groupService;

    public StudentFlmPreferencesService(JdbcTemplate jdbc) { this(jdbc, null); }

    @Autowired
    public StudentFlmPreferencesService(JdbcTemplate jdbc, FlmSubjectGroupService groupService) {
        this.jdbc = jdbc;
        this.groupService = groupService;
        ensureTable();
    }

    private void ensureTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS student_curriculum_preferences ("
                    + "student_id CHAR(36) PRIMARY KEY, combo_code VARCHAR(64) NULL, english_prep VARCHAR(32) NULL, "
                    + "musical_instrument VARCHAR(32) NULL, physical_education VARCHAR(32) NULL, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            try { jdbc.execute("ALTER TABLE student_curriculum_preferences ADD COLUMN physical_education VARCHAR(32) NULL"); }
            catch (RuntimeException ignored) { /* already exists */ }
        } catch (RuntimeException ignored) { /* minimal test schema */ }
    }

    public PreferencesView get(UUID studentId, String majorCode) { return get(studentId, majorCode, null); }

    public PreferencesView get(UUID studentId, String majorCode, String cohort) {
        Stored s = loadStored(studentId);
        return new PreferencesView(s.comboCode(), s.englishPrep(), s.musicalInstrument(), s.physicalEducation(),
                resolveCombos(majorCode, cohort), resolveEnglishOptions(), resolveInstruments(), resolvePhysicalEdOptions());
    }

    @Transactional
    public PreferencesView save(UUID studentId, String majorCode, String comboCode,
            String englishPrep, String musicalInstrument, String physicalEducation) {
        String c = norm(comboCode), e = norm(englishPrep), m = norm(musicalInstrument), p = norm(physicalEducation);
        int updated = jdbc.update("UPDATE student_curriculum_preferences SET combo_code = ?, english_prep = ?, "
                + "musical_instrument = ?, physical_education = ?, updated_at = CURRENT_TIMESTAMP WHERE student_id = ?",
                c, e, m, p, studentId.toString());
        if (updated == 0) {
            jdbc.update("INSERT INTO student_curriculum_preferences "
                    + "(student_id, combo_code, english_prep, musical_instrument, physical_education) VALUES (?, ?, ?, ?, ?)",
                    studentId.toString(), c, e, m, p);
        }
        return get(studentId, majorCode, null);
    }

    public List<CurriculumCourse> apply(UUID studentId, String majorCode, List<CurriculumCourse> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Stored prefs = loadStored(studentId);
        if (prefs.comboCode() == null && prefs.englishPrep() == null
                && prefs.musicalInstrument() == null && prefs.physicalEducation() == null) return raw;
        ComboOption combo = resolveComboByCode(majorCode, prefs.comboCode());
        List<SlotCourse> peSlots = resolvePhysicalEdSlots(prefs.physicalEducation());
        Map<String, String> instrumentMap = new LinkedHashMap<>();
        for (NamedOption opt : resolveInstruments()) instrumentMap.put(opt.code(), opt.label());

        List<CurriculumCourse> resolved = new ArrayList<>(raw.size());
        for (CurriculumCourse course : raw) {
            String upper = course.code() == null ? "" : course.code().toUpperCase(Locale.ROOT);
            if ("PEN".equals(upper) && prefs.englishPrep() != null) {
                if ("EXEMPT".equalsIgnoreCase(prefs.englishPrep())) continue;
                resolved.addAll(resolveEnglishChain(prefs.englishPrep(), course));
                continue;
            }
            if (upper.startsWith("TMI_ELE") && prefs.musicalInstrument() != null) {
                String label = instrumentMap.getOrDefault(prefs.musicalInstrument(), prefs.musicalInstrument());
                resolved.add(resolveSlot(null, prefs.musicalInstrument(), label, course.credits(), course, "Musical Instrument"));
                continue;
            }
            if (upper.startsWith("PHE_COM") || upper.startsWith("PHE_")) {
                if (!peSlots.isEmpty()) {
                    Matcher pm = COMBO_SLOT.matcher(upper);
                    int idx = pm.find() ? Integer.parseInt(pm.group(1)) - 1 : 0;
                    if (idx >= 0 && idx < peSlots.size()) {
                        SlotCourse s = peSlots.get(idx);
                        resolved.add(resolveSlot(s.courseId(), s.code(), s.name(), s.credits(), course, "Physical Education"));
                        continue;
                    }
                }
                resolved.add(course);
                continue;
            }
            Matcher matcher = COMBO_SLOT.matcher(upper);
            if (matcher.find() && combo != null) {
                int slotIdx = Integer.parseInt(matcher.group(1)) - 1;
                if (slotIdx >= 0 && slotIdx < combo.courses().size()) {
                    SlotCourse s = combo.courses().get(slotIdx);
                    resolved.add(resolveSlot(s.courseId(), s.code(), s.name(), s.credits(), course, combo.label()));
                    continue;
                }
            }
            resolved.add(course);
        }
        return resolved;
    }

    private CurriculumCourse resolveSlot(String courseId, String code, String fallbackName, int fallbackCredits,
            CurriculumCourse placeholder, String groupLabel) {
        if (courseId != null && !courseId.isBlank()) {
            return new CurriculumCourse(courseId, code, fallbackName, fallbackCredits, placeholder.semesterNumber(), false, groupLabel);
        }
        try {
            List<CurriculumCourse> found = jdbc.query(
                    "SELECT id, code, name, credits FROM courses WHERE UPPER(code) = ? LIMIT 1",
                    (rs, n) -> new CurriculumCourse(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), placeholder.semesterNumber(), false, groupLabel),
                    code.strip().toUpperCase(Locale.ROOT));
            if (!found.isEmpty()) return found.get(0);
        } catch (RuntimeException ignored) { /* fall back to placeholder id */ }
        return new CurriculumCourse(placeholder.courseId(), code, fallbackName, fallbackCredits,
                placeholder.semesterNumber(), false, groupLabel);
    }

    private List<ComboOption> resolveCombos(String major, String cohort) { return groupService == null ? List.of() : groupService.findCombosFor(major, cohort); }
    private ComboOption resolveComboByCode(String major, String code) { return code == null || groupService == null ? null : groupService.findComboByCode(code); }
    private List<NamedOption> resolveInstruments() { return groupService == null ? List.of() : groupService.findInstruments(); }
    private List<NamedOption> resolvePhysicalEdOptions() {
        return groupService == null ? List.of() : groupService.findPhysicalEdCombos().stream().map(c -> new NamedOption(c.code(), c.label())).toList();
    }
    private List<SlotCourse> resolvePhysicalEdSlots(String code) {
        ComboOption db = code == null || code.isBlank() || groupService == null ? null : groupService.findComboByCode(code);
        return db != null ? db.courses() : List.of();
    }

    private List<CurriculumCourse> resolveEnglishChain(String startCode, CurriculumCourse placeholder) {
        String upper = startCode.strip().toUpperCase(Locale.ROOT);
        CurriculumCourse fallback = resolveSlot(null, upper, upper, placeholder.credits(), placeholder, "English Prep");
        return FlmEnglishTrackResolver.resolveChain(jdbc, startCode, placeholder, fallback);
    }

    private List<NamedOption> resolveEnglishOptions() {
        return FlmEnglishTrackResolver.resolveOptions(jdbc);
    }

    private Stored loadStored(UUID studentId) {
        try {
            return jdbc.query(
                    "SELECT combo_code, english_prep, musical_instrument, physical_education FROM student_curriculum_preferences WHERE student_id = ?",
                    (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                    studentId.toString()).stream().findFirst().orElse(new Stored(null, null, null, null));
        } catch (RuntimeException ignored) { return new Stored(null, null, null, null); }
    }
    private static String norm(String v) { return v == null || v.isBlank() ? null : v.strip(); }
    private record Stored(String comboCode, String englishPrep, String musicalInstrument, String physicalEducation) {}
    public record SlotCourse(String courseId, String code, String name, int credits) {
        public SlotCourse(String code, String name, int credits) { this(null, code, name, credits); }
    }
    public record ComboOption(String code, String label, List<SlotCourse> courses) {}
    public record NamedOption(String code, String label) {}
    public record PreferencesView(String comboCode, String englishPrep, String musicalInstrument,
            String physicalEducation, List<ComboOption> combos, List<NamedOption> englishOptions,
            List<NamedOption> instrumentOptions, List<NamedOption> physicalEducationOptions) {}
}
