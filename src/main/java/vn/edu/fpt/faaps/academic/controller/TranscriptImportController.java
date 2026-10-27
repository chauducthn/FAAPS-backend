package vn.edu.fpt.faaps.academic.controller;

import vn.edu.fpt.faaps.academic.dto.TranscriptDtos;
import vn.edu.fpt.faaps.academic.service.TranscriptImportService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.*;

@RestController
@RequestMapping("/api/student/transcript/imports")
public class TranscriptImportController {
    private final TranscriptImportService imports;

    public TranscriptImportController(TranscriptImportService imports) {
        this.imports = imports;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview preview(@AuthenticationPrincipal AuthSession student,
            @RequestPart("file") MultipartFile file) throws IOException {
        return imports.preview(student.id(), file);
    }

    @PostMapping("/{id}/confirm")
    public ImportResult confirm(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) {
        return imports.confirm(student.id(), id);
    }

    @GetMapping
    public List<ImportHistory> history(@AuthenticationPrincipal AuthSession student) {
        return imports.history(student.id());
    }

    @GetMapping("/{id}")
    public ImportPreview detail(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) {
        return imports.detail(student.id(), id);
    }
}


