package vn.edu.fpt.faaps.notification.controller;

import vn.edu.fpt.faaps.notification.service.NotificationService;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping("/api/student/notifications")
public class StudentNotificationController {
    private final NotificationService notifications;
    public StudentNotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public NotificationService.Page list(@AuthenticationPrincipal AuthSession user,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return notifications.list(user.id(), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthSession user) {
        return Map.of("unreadCount", notifications.unreadCount(user.id()));
    }

    @PatchMapping("/{id}/read")
    public Map<String, Boolean> read(@AuthenticationPrincipal AuthSession user, @PathVariable UUID id) {
        return Map.of("updated", notifications.read(user.id(), id));
    }

    @PatchMapping("/read-all")
    public Map<String, Integer> readAll(@AuthenticationPrincipal AuthSession user) {
        return Map.of("updated", notifications.readAll(user.id()));
    }
}
