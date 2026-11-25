package vn.edu.fpt.faaps.advising.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdvisingConversationService {
    private final AdvisingConversationRepository repo;

    public AdvisingConversationService(JdbcTemplate jdbc) {
        this.repo = new AdvisingConversationRepository(jdbc);
    }

    public List<Request> list(UUID actorId, boolean advisor) {
        return list(actorId, advisor, null, null);
    }

    public List<Request> list(UUID actorId, boolean advisor, String statusFilter, String search) {
        return repo.findAll(actorId, advisor).stream()
                .filter(req -> matchesStatus(req, statusFilter))
                .filter(req -> matchesSearch(req, search))
                .toList();
    }

    public Page listPaged(UUID actorId, boolean advisor, String statusFilter, String search, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid page or size");
        }
        List<Request> filtered = list(actorId, advisor, statusFilter, search);
        long total = filtered.size();
        int fromIdx = Math.min(page * size, filtered.size());
        int toIdx = Math.min(fromIdx + size, filtered.size());
        List<Request> items = filtered.subList(fromIdx, toIdx);
        long totalPages = (total + size - 1) / size;
        return new Page(items, page, size, total, totalPages);
    }

    public Detail detail(UUID actorId, boolean advisor, UUID requestId) {
        Request request = repo.findOne(actorId, advisor, requestId);
        List<Message> messages = repo.findMessages(requestId, advisor);
        return new Detail(request, messages);
    }

    @Transactional
    public Detail addMessage(UUID actorId, boolean advisor, UUID requestId, String content, boolean internal) {
        if (content == null || content.isBlank() || content.length() > 10_000) {
            throw new IllegalArgumentException("Message must have 1 to 10000 characters");
        }
        repo.lockRequest(requestId);
        Request request = repo.findOne(actorId, advisor, requestId);
        if (isClosed(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is closed");
        }
        repo.insertMessage(requestId, actorId, content.strip(), advisor && internal);
        if (advisor && !internal) {
            repo.markInProgressIfPending(requestId);
            repo.notifyUser(request.studentId(), "Advisor replied", "Your academic advisor replied to your request.");
        } else {
            repo.touchRequest(requestId);
            if (!advisor && request.advisorId() != null) {
                repo.notifyUser(request.advisorId(), "Student replied", "A student replied to an advising request.");
            }
        }
        return detail(actorId, advisor, requestId);
    }

    @Transactional
    public Detail changeStatus(UUID actorId, boolean advisor, UUID requestId, String status, String summary) {
        repo.lockRequest(requestId);
        Request request = repo.findOne(actorId, advisor, requestId);
        validateStatusChange(advisor, request.status(), status, summary);
        repo.updateStatus(requestId, status, summary);
        if (advisor) {
            repo.notifyUser(request.studentId(), "Advising request updated", "Your advising request status changed.");
        } else if (request.advisorId() != null) {
            repo.notifyUser(request.advisorId(), "Advising request cancelled", "A student cancelled an advising request.");
        }
        return detail(actorId, advisor, requestId);
    }

    private static void validateStatusChange(boolean advisor, String currentStatus, String status, String summary) {
        if (advisor) {
            if (!Set.of("IN_PROGRESS", "RESOLVED", "CANCELLED").contains(status)) {
                throw new IllegalArgumentException("Advisor status must be IN_PROGRESS, RESOLVED, or CANCELLED");
            }
            if (isClosed(currentStatus)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is closed");
            }
            if (("RESOLVED".equals(status) || "CANCELLED".equals(status)) && (summary == null || summary.isBlank())) {
                throw new IllegalArgumentException("Resolution summary is required");
            }
            if (summary != null && summary.length() > 10_000) {
                throw new IllegalArgumentException("Resolution summary is too long");
            }
        } else {
            if (!"CANCELLED".equals(status)) {
                throw new IllegalArgumentException("Student can only cancel a request");
            }
            if (isClosed(currentStatus)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is closed");
            }
        }
    }

    private static boolean isClosed(String status) {
        return "RESOLVED".equals(status) || "CANCELLED".equals(status);
    }

    private static boolean matchesStatus(Request req, String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank() || "ALL".equalsIgnoreCase(statusFilter.strip())) {
            return true;
        }
        return req.status() != null && req.status().equalsIgnoreCase(statusFilter.strip());
    }

    private static boolean matchesSearch(Request req, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String kw = search.strip().toLowerCase(Locale.ROOT);
        return (req.title() != null && req.title().toLowerCase(Locale.ROOT).contains(kw))
                || (req.studentName() != null && req.studentName().toLowerCase(Locale.ROOT).contains(kw))
                || (req.studentCode() != null && req.studentCode().toLowerCase(Locale.ROOT).contains(kw));
    }

    public record Request(String id, String studentId, String studentName, String studentCode,
            String advisorId, String advisorName, String studyPlanId, String title,
            String description, String requestType, String status, String resolutionSummary,
            LocalDateTime resolvedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public Request(String id, String studentId, String studentName, String advisorId,
                String advisorName, String title, String description, String requestType, String status,
                String resolutionSummary, LocalDateTime createdAt, LocalDateTime updatedAt) {
            this(id, studentId, studentName, null, advisorId, advisorName, null, title,
                    description, requestType, status, resolutionSummary, null, createdAt, updatedAt);
        }
    }

    public record Message(String id, String senderId, String senderName, String content,
            boolean internal, LocalDateTime createdAt) {}

    public record Detail(Request request, List<Message> messages) {}

    public record Page(List<Request> items, int page, int size, long totalElements, long totalPages) {}

    public record Attachment(String id, String fileName, String url, long size, LocalDateTime uploadedAt) {}
}
