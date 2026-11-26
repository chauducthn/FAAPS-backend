package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.ComboOption;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.NamedOption;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.SlotCourse;

/** Standard FPTU FLM options for Sem 0 electives and Sem 5+ specialization combos. */
final class FlmComboCatalog {
    static final Map<String, List<ComboOption>> COMBOS_BY_MAJOR = Map.of(
            "SE", List.of(
                    new ComboOption("SE_DOTNET", ".NET & Enterprise", List.of(
                            new SlotCourse("PRN212", "Basic Cross-Platform App Programming with .NET", 3),
                            new SlotCourse("PRN222", "Advanced Cross-Platform App Programming with .NET", 3),
                            new SlotCourse("PRN232", "Cross-Platform Back-End Application with .NET", 3),
                            new SlotCourse("PRM392", "Mobile Programming", 3))),
                    new ComboOption("SE_JAVA", "Java Web & Microservices", List.of(
                            new SlotCourse("HSF302", "Hibernate & Spring Framework", 3),
                            new SlotCourse("SBA301", "Single-Page Application with Spring Boot", 3),
                            new SlotCourse("MSS301", "Microservices Architecture with Spring Cloud", 3),
                            new SlotCourse("PRC392c", "Cloud Computing", 3))),
                    new ComboOption("SE_NODE", "NodeJS & React Full-Stack", List.of(
                            new SlotCourse("FER202", "Front-End Web Development with React", 3),
                            new SlotCourse("SDN302", "Server-Side Development with NodeJS & Express", 3),
                            new SlotCourse("MMA301", "Multiplatform Mobile App Development", 3),
                            new SlotCourse("WDP301", "Web Development Project", 3))),
                    new ComboOption("SE_SAP", "SAP / ERP Consulting", List.of(
                            new SlotCourse("SAP312", "ERP Systems: Concepts and Practice with SAP", 3),
                            new SlotCourse("SAP322", "SAP ABAP Programming", 3),
                            new SlotCourse("SAP332", "SAP Fiori & UI5 Development", 3),
                            new SlotCourse("SAP342", "SAP S/4HANA Integration Project", 3))),
                    new ComboOption("SE_AI", "Applied AI for Software Engineers", List.of(
                            new SlotCourse("AIL303m", "Machine Learning", 3),
                            new SlotCourse("DPL302m", "Deep Learning", 3),
                            new SlotCourse("NLP302c", "Applied NLP with LLM & RAG", 3),
                            new SlotCourse("AIP391", "AI Programming Project", 3)))),
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

    static final List<NamedOption> INSTRUMENTS = List.of(
            new NamedOption("TMI101", "TMI101 — Dan Tranh (Zither)"),
            new NamedOption("TMI102", "TMI102 — Dan Bau (Monochord)"),
            new NamedOption("TMI103", "TMI103 — Dan Nguyet (Moon Lute)"),
            new NamedOption("TMI104", "TMI104 — Sao Truc (Bamboo Flute)"),
            new NamedOption("TMI105", "TMI105 — Dan Ty Ba (Pipa)"),
            new NamedOption("TMI106", "TMI106 — Dan Nhi (Two-String Fiddle)"));

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
}
