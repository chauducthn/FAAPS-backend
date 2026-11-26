package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService.CurriculumCourse;

/**
 * Persists FLM personalization choices (Sem 0 English & Instrument, Sem 5+ Combo)
 * and resolves placeholder curriculum courses (PEN, TMI_ELE, *_COM*1..4) into concrete courses.
 */
@Service
public class StudentFlmPreferencesService {
    private static final Pattern COMBO_SLOT = Pattern.compile("COM\\*?(\\d)", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;

    public StudentFlmPreferencesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTable();
    }

    private void ensureTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS student_curriculum_preferences (
                        student_id CHAR(36) PRIMARY KEY,
                        combo_code VARCHAR(64) NULL,
                        english_prep VARCHAR(32) NULL,
                        musical_instrument VARCHAR(32) NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        } catch (RuntimeException ignored) {
            // Minimal test schemas may not allow DDL.
        }
    }

    public PreferencesView get(UUID studentId, String majorCode) {
        Stored stored = loadStored(studentId);
        return new PreferencesView(
                stored.comboCode(),
                stored.englishPrep(),
                stored.musicalInstrument(),
                FlmComboCatalog.combosFor(majorCode),
                FlmComboCatalog.ENGLISH_MODES,
                FlmComboCatalog.INSTRUMENTS);
    }

    @Transactional
    public PreferencesView save(UUID studentId, String majorCode, String comboCode,
            String englishPrep, String musicalInstrument) {
        String c = normalize(comboCode);
        String e = normalize(englishPrep);
        String m = normalize(musicalInstrument);
        int updated = jdbc.update("""
                UPDATE student_curriculum_preferences
                SET combo_code = ?, english_prep = ?, musical_instrument = ?, updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ?
                """, c, e, m, studentId.toString());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO student_curriculum_preferences
                        (student_id, combo_code, english_prep, musical_instrument)
                    VALUES (?, ?, ?, ?)
                    """, studentId.toString(), c, e, m);
        }
        return get(studentId, majorCode);
    }

    public List<CurriculumCourse> apply(UUID studentId, String majorCode, List<CurriculumCourse> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Stored prefs = loadStored(studentId);
        if (prefs.comboCode() == null && prefs.englishPrep() == null && prefs.musicalInstrument() == null) {
            return raw;
        }
        ComboOption combo = FlmComboCatalog.findCombo(majorCode, prefs.comboCode());
        Map<String, String> instrumentMap = new LinkedHashMap<>();
        for (NamedOption opt : FlmComboCatalog.INSTRUMENTS) instrumentMap.put(opt.code(), opt.label());

        List<CurriculumCourse> resolved = new ArrayList<>(raw.size());
        for (CurriculumCourse course : raw) {
            String upper = course.code() == null ? "" : course.code().toUpperCase(Locale.ROOT);
            if ("PEN".equals(upper) && prefs.englishPrep() != null) {
                if ("EXEMPT".equalsIgnoreCase(prefs.englishPrep())) continue;
                String code = "LUK".equalsIgnoreCase(prefs.englishPrep()) ? "LUK" : "TRS601";
                String name = "LUK".equalsIgnoreCase(prefs.englishPrep())
                        ? "Little UK Preparation English" : "Transition English (TRS)";
                resolved.add(new CurriculumCourse(course.courseId(), code, name,
                        course.credits(), course.semesterNumber(), false, "English Prep"));
                continue;
            }
            if (upper.startsWith("TMI_ELE") && prefs.musicalInstrument() != null) {
                String label = instrumentMap.getOrDefault(prefs.musicalInstrument(), prefs.musicalInstrument());
                resolved.add(new CurriculumCourse(course.courseId(), prefs.musicalInstrument(), label,
                        course.credits(), course.semesterNumber(), false, "Musical Instrument"));
                continue;
            }
            Matcher matcher = COMBO_SLOT.matcher(upper);
            if (matcher.find() && combo != null) {
                int slotIdx = Integer.parseInt(matcher.group(1)) - 1;
                if (slotIdx >= 0 && slotIdx < combo.courses().size()) {
                    SlotCourse slot = combo.courses().get(slotIdx);
                    resolved.add(new CurriculumCourse(course.courseId(), slot.code(), slot.name(),
                            slot.credits(), course.semesterNumber(), false, combo.label()));
                    continue;
                }
            }
            resolved.add(course);
        }
        return resolved;
    }

    private Stored loadStored(UUID studentId) {
        try {
            return jdbc.query("""
                    SELECT combo_code, english_prep, musical_instrument
                    FROM student_curriculum_preferences WHERE student_id = ?
                    """, (rs, row) -> new Stored(
                    rs.getString("combo_code"),
                    rs.getString("english_prep"),
                    rs.getString("musical_instrument")), studentId.toString())
                    .stream().findFirst().orElse(new Stored(null, null, null));
        } catch (RuntimeException ignored) {
            return new Stored(null, null, null);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record Stored(String comboCode, String englishPrep, String musicalInstrument) {}
    public record SlotCourse(String code, String name, int credits) {}
    public record ComboOption(String code, String label, List<SlotCourse> courses) {}
    public record NamedOption(String code, String label) {}
    public record PreferencesView(
            String comboCode,
            String englishPrep,
            String musicalInstrument,
            List<ComboOption> combos,
            List<NamedOption> englishOptions,
            List<NamedOption> instrumentOptions) {}
}
