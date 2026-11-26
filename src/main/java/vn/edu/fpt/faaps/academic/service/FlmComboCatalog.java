package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.ComboOption;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.NamedOption;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.SlotCourse;

/** Standard FPTU FLM options for Sem 0 electives, Physical Education, and Sem 5+ specialization combos. */
final class FlmComboCatalog {
    static final Map<String, List<ComboOption>> COMBOS_BY_MAJOR = Map.of(
            "SE", List.of(
                    new ComboOption("SE_DOTNET", "SE_COM3.3 — .NET Programming", List.of(
                            new SlotCourse("PRN212", "Basic Cross-Platform Application Programming with .NET", 3),
                            new SlotCourse("PRN222", "Advanced Cross-Platform Application Programming with .NET", 3),
                            new SlotCourse("PRU213", "Game Programming with C#", 3),
                            new SlotCourse("PRN232", "Building Cross-Platform Back-End Application with .NET", 3))),
                    new ComboOption("SE_NODE", "SE_COM4.1 — React / NodeJS Full-Stack", List.of(
                            new SlotCourse("FER202", "Front-End Web Development with React", 3),
                            new SlotCourse("SDN302", "Server-Side Development with NodeJS, Express & MongoDB", 3),
                            new SlotCourse("MMA301", "Multiplatform Mobile App Development", 3),
                            new SlotCourse("WDP301", "Web Development Project", 3))),
                    new ComboOption("SE_JAVA", "SE_COM10.1 — Intensive Java & Microservices", List.of(
                            new SlotCourse("HSF302", "Working with Spring Framework", 3),
                            new SlotCourse("SBA301", "Integrate Single Page Application with Spring Boot", 3),
                            new SlotCourse("MSS301", "Microservices with Spring Cloud", 3),
                            new SlotCourse("PRM392", "Mobile Programming", 3))),
                    new ComboOption("SE_AI", "SE_COM7.1 — Applied AI", List.of(
                            new SlotCourse("AIL303m", "Machine Learning", 3),
                            new SlotCourse("DPL302m", "Deep Learning", 3),
                            new SlotCourse("NLP302c", "Applied NLP with LLM & RAG", 3),
                            new SlotCourse("AIP391", "AI Programming Project", 3))),
                    new ComboOption("SE_SAP", "SE_COM9 — SAP / ERP Consulting", List.of(
                            new SlotCourse("SAP311", "SAP General 1", 3),
                            new SlotCourse("SAP321", "SAP General 2", 3),
                            new SlotCourse("SAP331", "SAP Configuration", 3),
                            new SlotCourse("SAP341", "SAP Application Development with ABAP", 3))),
                    new ComboOption("SE_ECI", "SE_COM11 — IC Design / Electronic Circuits (ECI)", List.of(
                            new SlotCourse("ECI101", "Introduction to Electronic Components and Circuits", 3),
                            new SlotCourse("ICD201", "Digital Integrated Circuit Design", 3),
                            new SlotCourse("ICD301", "VLSI Design & Verification", 3),
                            new SlotCourse("ICD302", "IC Physical Design & Testing", 3))),
                    new ComboOption("SE_GAME", "SE_COM12 — Game Development", List.of(
                            new SlotCourse("PRU212", "C# Programming and Unity", 3),
                            new SlotCourse("PRU213", "Game Programming with C#", 3),
                            new SlotCourse("PRU221m", "Object-Oriented Programming for Unity Games", 3),
                            new SlotCourse("GAM301", "Game Development Project", 3))),
                    new ComboOption("SE_JP_IT", "SE_COM5.1 — Japanese Bridge Engineer (IT Japanese)", List.of(
                            new SlotCourse("JPD316", "Intermediate Japanese 1 (B1/B2)", 6),
                            new SlotCourse("JPD326", "Intermediate Japanese 2 (B2.1)", 6),
                            new SlotCourse("JFE301", "Japanese IT Fundamentals", 3),
                            new SlotCourse("JIS401", "IT Japanese in Software Engineering", 3))),
                    new ComboOption("SE_JP_ADV", "SE_COM5.2 — Japanese Bridge Engineer (Advanced JP)", List.of(
                            new SlotCourse("JPD316", "Intermediate Japanese 1 (B1/B2)", 6),
                            new SlotCourse("JPD326", "Intermediate Japanese 2 (B2.1)", 6),
                            new SlotCourse("JPD336", "Intermediate Japanese 2 (B2.2)", 6),
                            new SlotCourse("JPD346", "Intermediate Japanese 2 (B2/C1)", 6))),
                    new ComboOption("SE_KR", "SE_COM6 — IT & Korean Language", List.of(
                            new SlotCourse("KOR311", "Intermediate Korean Language 1", 3),
                            new SlotCourse("KOR321", "Intermediate Korean Language 2", 3),
                            new SlotCourse("KOR411", "Intermediate Korean Language 3", 3),
                            new SlotCourse("KOR421", "IT Korean for Software Engineering", 3)))),
            "AI", List.of(
                    new ComboOption("AI_NLP", "NLP & Large Language Models", List.of(
                            new SlotCourse("NLP301c", "Natural Language Processing", 3),
                            new SlotCourse("NLP302c", "Applied NLP with LLM & RAG", 3),
                            new SlotCourse("DPL302m", "Deep Learning", 3),
                            new SlotCourse("AIP391", "AI Programming Project", 3))),
                    new ComboOption("AI_CV", "Computer Vision & Robotics", List.of(
                            new SlotCourse("CPV301", "Computer Vision", 3),
                            new SlotCourse("DPL302m", "Deep Learning", 3),
                            new SlotCourse("ROB301", "Intelligent Robotics", 3),
                            new SlotCourse("AIP391", "AI Programming Project", 3)))),
            "IA", List.of(
                    new ComboOption("IA_NET", "Network & Cloud Security", List.of(
                            new SlotCourse("NWC301", "Enterprise Network Defense", 3),
                            new SlotCourse("PRC392c", "Cloud Computing Security", 3),
                            new SlotCourse("SOC301", "Security Operations Center", 3),
                            new SlotCourse("PEN301", "Penetration Testing", 3)))));

    static final Map<String, List<SlotCourse>> PHYSICAL_ED_SLOTS = Map.of(
            "VOVINAM", List.of(
                    new SlotCourse("VOV114", "Vovinam 1", 2),
                    new SlotCourse("VOV124", "Vovinam 2", 2),
                    new SlotCourse("VOV134", "Vovinam 3", 2)),
            "CHESS", List.of(
                    new SlotCourse("COV111", "Cờ Vua 1 (Chess 1)", 2),
                    new SlotCourse("COV121", "Cờ Vua 2 (Chess 2)", 2),
                    new SlotCourse("COV131", "Cờ Vua 3 (Chess 3)", 2)));

    static final List<NamedOption> PHYSICAL_ED_OPTIONS = List.of(
            new NamedOption("VOVINAM", "Vovinam (VOV114 – VOV134)"),
            new NamedOption("CHESS", "Cờ vua / Chess (COV111 – COV131)"));

    static final List<NamedOption> INSTRUMENTS = List.of(
            new NamedOption("ĐTR102", "ĐTR102 — Đàn Tranh (Zither)"),
            new NamedOption("ĐBA102", "ĐBA102 — Đàn Bầu (Monochord)"),
            new NamedOption("ĐNG102", "ĐNG102 — Đàn Nguyệt (Moon Lute)"),
            new NamedOption("ĐSA102", "ĐSA102 — Sáo Trúc (Bamboo Flute)"),
            new NamedOption("ĐTB102", "ĐTB102 — Đàn Tỳ Bà (Pipa)"),
            new NamedOption("ĐNH102", "ĐNH102 — Đàn Nhị (Two-String Fiddle)"),
            new NamedOption("TRG102", "TRG102 — Trống Dân Tộc (Traditional Drum)"));

    static final List<NamedOption> ENGLISH_MODES = List.of(
            new NamedOption("TRS", "Transition English (TRS401 – TRS601)"),
            new NamedOption("LUK", "Little UK English Prep (LUK1 – LUK5)"),
            new NamedOption("EXEMPT", "Exempt (IELTS >= 6.0 / Equivalent)"));

    private FlmComboCatalog() {}

    static List<ComboOption> combosFor(String majorCode) {
        if (majorCode == null || majorCode.isBlank()) return COMBOS_BY_MAJOR.get("SE");
        return COMBOS_BY_MAJOR.getOrDefault(majorCode.toUpperCase(Locale.ROOT), COMBOS_BY_MAJOR.get("SE"));
    }

    static ComboOption findCombo(String majorCode, String comboCode) {
        if (comboCode == null) return null;
        for (ComboOption opt : combosFor(majorCode)) {
            if (opt.code().equalsIgnoreCase(comboCode)) return opt;
        }
        for (List<ComboOption> list : COMBOS_BY_MAJOR.values()) {
            for (ComboOption opt : list) {
                if (opt.code().equalsIgnoreCase(comboCode)) return opt;
            }
        }
        return null;
    }

    static List<SlotCourse> physicalEdSlots(String code) {
        if (code == null || code.isBlank()) return List.of();
        return PHYSICAL_ED_SLOTS.getOrDefault(code.toUpperCase(Locale.ROOT), List.of());
    }
}
