package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.AdminSemesterService;

@RestController
@RequestMapping("/api/admin/semesters")
public class AdminSemesterController {
    private final AdminSemesterService semesters;

    public AdminSemesterController(AdminSemesterService semesters) { this.semesters = semesters; }

    @GetMapping
    public CurriculumController.ApiResponse<List<AdminSemesterService.SemesterItem>> list() {
        return CurriculumController.ApiResponse.success("Semesters loaded", semesters.list());
    }

    @PostMapping
    public ResponseEntity<CurriculumController.ApiResponse<AdminSemesterService.SemesterItem>> create(
            @Valid @RequestBody SemesterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CurriculumController.ApiResponse.success(
                "Semester created", semesters.create(request.toInput())));
    }

    @PutMapping("/{id}")
    public CurriculumController.ApiResponse<AdminSemesterService.SemesterItem> update(
            @PathVariable UUID id, @Valid @RequestBody SemesterRequest request) {
        return CurriculumController.ApiResponse.success("Semester updated", semesters.update(id, request.toInput()));
    }

    public record SemesterRequest(@NotBlank String code, @NotBlank String name,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
        AdminSemesterService.SemesterInput toInput() {
            return new AdminSemesterService.SemesterInput(code, name, startDate, endDate);
        }
    }
}
