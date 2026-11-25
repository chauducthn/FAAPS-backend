package vn.edu.fpt.faaps.infrastructure.mail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping("/api/admin/mail")
public class AdminMailController {

    private final EmailService emailService;

    public AdminMailController(EmailService emailService) {
        this.emailService = emailService;
    }

    @GetMapping("/status")
    public EmailService.MailStatus status() {
        return emailService.status();
    }

    @PostMapping("/test")
    public Map<String, Object> sendTest(@Valid @RequestBody TestEmailRequest body) {
        emailService.sendTestEmail(body.toEmail());
        return Map.of("success", true, "message", "Test email sent to " + body.toEmail().strip());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> smtpFailure(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError.of("SMTP_ERROR", ex.getMessage()));
    }

    public record TestEmailRequest(@NotBlank @Email String toEmail) {}
}
