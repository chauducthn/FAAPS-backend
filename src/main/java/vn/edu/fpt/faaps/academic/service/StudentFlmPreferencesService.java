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
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS student_curriculum_preferences (
                        student_id CHAR(36) PRIMARY KEY, combo_code VARCHAR(64) NULL, english_prep VARCHAR(32) NULL,
                        musical_instrument VARCHAR(32) NULL, physical_education VARCHAR(32) NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                    """);
            try { jdbc.execute("ALTER TABLE student_curriculum_preferences ADD COLUMN physical_education VARCHAR(32) NULL"); }
            catch (RuntimeException ignored) { /* Column already exists. */ }
        } catch (RuntimeException ignored) { /* Minimal test schemas may not allow DDL. */ }
    }

    public PreferencesView get(UUID studentId, String majorCode) { return get(studentId, majorCode, null); }

    public PreferencesView get(UUID studentId, String majorCode, String cohort) {
        Stored s = loadStored(studentId);
        return new PreferencesView(s.comboCode(), s.englishPrep(), s.musicalInstrument(), s.physicalEducation(),
                resolveCombos(majorCode, cohort), resolveEnglishOptions(),
                resolveInstruments(), resolvePhysicalEdOptions());
    }

    @Transactional
    public PreferencesView save(UUID studentId, String majorCode, String comboCode,
            String englishPrep, String musicalInstrument, String physicalEducation) {
        String c = norm(comboCode), e = norm(englishPrep), m = norm(musicalInstrument), p = norm(physicalEducation);
        int updated = jdbc.update("""
                UPDATE student_curriculum_preferences
                SET combo_code = ?, english_prep = ?, musical_instrument = ?,
                    physical_education = ?, updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ?
                """, c, e, m, p, studentId.toString());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO student_curriculum_preferences
                        (student_id, combo_code, english_prep, musical_instrument, physical_education)
                    VALUES (?, ?, ?, ?, ?)
                    """, studentId.toString(), c, e, m, p);
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

        Map<String, String> englishMap = new LinkedHashMap<>();
        for (NamedOption opt : resolveEnglishOptions()) englishMap.put(opt.code(), opt.label());

        List<CurriculumCourse> resolved = new ArrayList<>(raw.size());
        for (CurriculumCourse course : raw) {
            String upper = course.code() == null ? "" : course.code().toUpperCase(Locale.ROOT);
            if ("PEN".equals(upper) && prefs.englishPrep() != null) {
                if ("EXEMPT".equalsIgnoreCase(prefs.englishPrep())) continue;
                String label = englishMap.getOrDefault(prefs.englishPrep(), prefs.englishPrep());
                String name = label.contains(" — ") ? label.substring(label.indexOf(" — ") + 3) : label;
                resolved.add(new CurriculumCourse(course.courseId(), prefs.englishPrep(), name,
                        course.credits(), course.semesterNumber(), false, "English Prep"));
                continue;
            }
            if (upper.startsWith("TMI_ELE") && prefs.musicalInstrument() != null) {
                String label = instrumentMap.getOrDefault(prefs.musicalInstrument(), prefs.musicalInstrument());
                resolved.add(new CurriculumCourse(course.courseId(), prefs.musicalInstrument(), label,
                        course.credits(), course.semesterNumber(), false, "Musical Instrument"));
                continue;
            }
            if (upper.startsWith("PHE_COM") || upper.startsWith("PHE_")) {
                if (!peSlots.isEmpty()) {
                    Matcher pm = COMBO_SLOT.matcher(upper);
                    int idx = pm.find() ? Integer.parseInt(pm.group(1)) - 1 : 0;
                    if (idx >= 0 && idx < peSlots.size()) {
                        SlotCourse s = peSlots.get(idx);
                        resolved.add(new CurriculumCourse(course.courseId(), s.code(), s.name(),
                                s.credits(), course.semesterNumber(), false, "Physical Education"));
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
                    resolved.add(new CurriculumCourse(course.courseId(), s.code(), s.name(),
                            s.credits(), course.semesterNumber(), false, combo.label()));
                    continue;
                }
            }
            resolved.add(course);
        }
        return resolved;
    }

    private List<ComboOption> resolveCombos(String majorCode, String cohort) {
        if (groupService == null) return List.of();
        return groupService.findCombosFor(majorCode, cohort);
    }

    private ComboOption resolveComboByCode(String majorCode, String comboCode) {
        if (comboCode == null || groupService == null) return null;
        return groupService.findComboByCode(comboCode);
    }

    private List<NamedOption> resolveInstruments() {
        if (groupService == null) return List.of();
        return groupService.findInstruments();
    }

    private List<NamedOption> resolvePhysicalEdOptions() {
        if (groupService == null) return List.of();
        return groupService.findPhysicalEdCombos().stream()
                .map(c -> new NamedOption(c.code(), c.label())).toList();
    }

    private List<SlotCourse> resolvePhysicalEdSlots(String code) {
        if (code == null || code.isBlank() || groupService == null) return List.of();
        ComboOption db = groupService.findComboByCode(code);
        return db != null ? db.courses() : List.of();
    }

    private List<NamedOption> resolveEnglishOptions() {
        try {
            List<NamedOption> fromDb = jdbc.query("""
                    SELECT code, name FROM courses
                    WHERE status = 'ACTIVE' AND UPPER(code) REGEXP '^(PEN|TRS|LUK)[0-9]'
                    ORDER BY code ASC
                    """, (rs, n) -> new NamedOption(rs.getString(1), rs.getString(1) + " — " + rs.getString(2)));
            if (fromDb.isEmpty()) return List.of();
            List<NamedOption> out = new ArrayList<>(fromDb.size() + 1);
            out.addAll(fromDb);
            out.add(new NamedOption("EXEMPT", "Exempt (IELTS >= 6.0 / Equivalent)"));
            return out;
        } catch (RuntimeException ignored) { return List.of(); }
    }

    private Stored loadStored(UUID studentId) {
        try {
            return jdbc.query("""
                    SELECT combo_code, english_prep, musical_instrument, physical_education
                    FROM student_curriculum_preferences WHERE student_id = ?
                    """, (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                    studentId.toString()).stream().findFirst().orElse(new Stored(null, null, null, null));
        } catch (RuntimeException ignored) { return new Stored(null, null, null, null); }
    }

    private static String norm(String v) { return v == null || v.isBlank() ? null : v.strip(); }

    private record Stored(String comboCode, String englishPrep, String musicalInstrument, String physicalEducation) {}
    public record SlotCourse(String code, String name, int credits) {}
    public record ComboOption(String code, String label, List<SlotCourse> courses) {}
    public record NamedOption(String code, String label) {}
    public record PreferencesView(String comboCode, String englishPrep, String musicalInstrument,
            String physicalEducation, List<ComboOption> combos, List<NamedOption> englishOptions,
            List<NamedOption> instrumentOptions, List<NamedOption> physicalEducationOptions) {}
}
