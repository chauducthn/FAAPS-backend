package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService;
import vn.edu.fpt.faaps.academic.service.StudentFlmPreferencesService.PreferencesView;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/student/profile/flm-preferences", "/student/profile/flm-preferences"})
public class StudentFlmPreferencesController {
    private final StudentAcademicService academics;
    private final StudentFlmPreferencesService preferences;

    public StudentFlmPreferencesController(StudentAcademicService academics,
            StudentFlmPreferencesService preferences) {
        this.academics = academics;
        this.preferences = preferences;
    }

    @GetMapping
    public PreferencesView get(@AuthenticationPrincipal AuthSession student) {
        String majorCode = resolveMajorCode(student);
        return preferences.get(student.id(), majorCode);
    }

    @PutMapping
    public PreferencesView save(@AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody UpdateFlmPreferencesRequest request) {
        String majorCode = resolveMajorCode(student);
        return preferences.save(student.id(), majorCode,
                request.comboCode(), request.englishPrep(), request.musicalInstrument());
    }

    private String resolveMajorCode(AuthSession student) {
        try {
            return academics.profile(student.id()).majorCode();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record UpdateFlmPreferencesRequest(
            @Size(max = 64) String comboCode,
            @Size(max = 32) String englishPrep,
            @Size(max = 32) String musicalInstrument) {}
}
