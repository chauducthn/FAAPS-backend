package vn.edu.fpt.faaps.academic.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules that decide whether two course codes stand for the same curriculum
 * requirement.
 *
 * <p>Courses and curricula are imported from external systems (FLM, FAP) and the
 * database is rebuilt from those exports. So the knowledge of "these two codes are the
 * same course" cannot live in hand-written rows: it is derived here, from the codes,
 * names and credits that every export carries.
 *
 * <p>Two relations are recognised, and both are deliberately conservative:
 *
 * <ul>
 *   <li><b>Code variant</b> — the same subject under a different delivery suffix:
 *       {@code IOT102} / {@code IoT102t}, {@code SWE201} / {@code SWE201c}. The base code
 *       ({@code ABC123}) must match, and when both names are known the credits and names
 *       must agree too, because FPTU reuses some base codes for unrelated courses
 *       ({@code ISM301} "ERP Systems" vs {@code ISM301c} "International Strategic
 *       Management").</li>
 *   <li><b>Replacement</b> — a renumbered successor in the same subject:
 *       {@code CSI104} "Introduction to Computer" replaced by {@code CSI106}
 *       "Introduction to Computer Science". The subject prefix, the credits and the
 *       level digit must match, and the names must be identical or one must extend the
 *       other without a level qualifier ({@code EBS211} "Hệ thống nhúng" is not replaced
 *       by {@code EBS311} "Hệ thống nhúng nâng cao", {@code DTG102} "Visual Design
 *       Tools" is not {@code DTG111} "Visual Design Tools 1").</li>
 * </ul>
 *
 * <p>A replacement is only ever applied in the context of one student and one
 * curriculum, and only when it is unambiguous: see
 * {@link CurriculumSubstitutionService#satisfiedCurriculumCourses}.
 */
public final class CourseMatchRules {
    /** {@code ABC123} followed by an optional delivery suffix ({@code c}, {@code m}, {@code t}…). */
    private static final Pattern CODE_RE = Pattern.compile("^([A-Z]{3})(\\d{3})([A-Z]{0,2})(?:[-_](?:OLD|NEW|V\\d+))?$");

    /** Words that mark a different level of the same subject, so they never extend a name. */
    private static final Set<String> LEVEL_WORDS = Set.of(
            "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x",
            "advanced", "basic", "intermediate", "elementary", "beginner", "upper", "pre",
            "level", "part", "nâng", "cao", "cơ", "bản", "trung", "cấp", "sơ", "nang", "co", "ban");

    private CourseMatchRules() {}

    /** A course as seen by the rules: code, name and credits. The name may be unknown. */
    public record CourseRef(String id, String code, String name, int credits) {
        public String upperCode() {
            return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        }
    }

    /** The parts of a standard FPTU course code. */
    public record CodeParts(String prefix, String number, String variant) {
        public String base() {
            return prefix + number;
        }

        /** The first digit of the course number: 1xx, 2xx… */
        public char level() {
            return number.charAt(0);
        }
    }

    /** Parses {@code code}; returns {@code null} for placeholders such as {@code SE_COM*4}. */
    public static CodeParts parse(String code) {
        if (code == null) return null;
        Matcher m = CODE_RE.matcher(code.strip().toUpperCase(Locale.ROOT));
        if (!m.matches()) return null;
        return new CodeParts(m.group(1), m.group(2), m.group(3));
    }

    /** {@code IOT102} for {@code IoT102t}; {@code null} when the code is not a standard code. */
    public static String baseCode(String code) {
        CodeParts parts = parse(code);
        return parts == null ? null : parts.base();
    }

    /**
     * Whether {@code a} and {@code b} are the same subject under two code variants.
     *
     * <p>Requires the same base code, the same credits and compatible names.
     */
    public static boolean isSameCourseVariant(CourseRef a, CourseRef b) {
        if (a == null || b == null) return false;
        if (a.upperCode().equals(b.upperCode())) return false;
        CodeParts pa = parse(a.code());
        CodeParts pb = parse(b.code());
        if (pa == null || pb == null || !pa.base().equals(pb.base())) return false;
        return a.credits() == b.credits() && namesCompatible(a.name(), b.name());
    }

    /**
     * Whether {@code taken} looks like the predecessor (or successor) of {@code required}.
     *
     * <p>Same subject prefix, same credits, same level digit, a different number and
     * compatible names. Symmetric.
     */
    public static boolean isLikelyReplacement(CourseRef taken, CourseRef required) {
        if (taken == null || required == null) return false;
        CodeParts pt = parse(taken.code());
        CodeParts pr = parse(required.code());
        if (pt == null || pr == null) return false;
        if (!pt.prefix().equals(pr.prefix()) || pt.number().equals(pr.number())) return false;
        if (pt.level() != pr.level()) return false;
        return taken.credits() == required.credits() && namesCompatible(taken.name(), required.name());
    }

    /**
     * Two names are compatible when, once normalised, they are equal, or one is a
     * whole-word prefix of the other and the extra words do not name another level
     * ("Introduction to Computer" / "Introduction to Computer Science" are compatible;
     * "Visual Design Tools" / "Visual Design Tools 1" are not).
     */
    public static boolean namesCompatible(String rawA, String rawB) {
        String a = normalizeName(rawA);
        String b = normalizeName(rawB);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (!longer.startsWith(shorter) || longer.charAt(shorter.length()) != ' ') return false;
        List<String> extra = Arrays.asList(longer.substring(shorter.length()).strip().split(" "));
        for (String word : extra) {
            if (word.chars().anyMatch(Character::isDigit) || LEVEL_WORDS.contains(word)) return false;
        }
        return true;
    }

    /** Lower case, {@code &} as "and", punctuation collapsed to single spaces. */
    public static String normalizeName(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace("&", " and ");
        return s.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
