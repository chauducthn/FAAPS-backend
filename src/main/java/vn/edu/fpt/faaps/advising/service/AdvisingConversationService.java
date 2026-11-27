package vn.edu.fpt.faaps.advising.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.advising.service.AdvisingRequestMapper.Viewer;

@Service
public class AdvisingConversationService {
    private static final Set<String> CLOSED = Set.of("RESOLVED", "CANCELLED", "REJECTED");
    private static final Set<String> ADVISOR_STATUSES = Set.of("IN_PROGRESS", "RESOLVED", "REJECTED", "CANCELLED");
    private static final Comparator<Request> NEWEST_FIRST = Comparator.comparing(
            (Request r) -> r.updatedAt() != null ? r.updatedAt() : r.createdAt(),
            Comparator.nullsLast(Comparator.reverseOrder()));
    private final AdvisingConversationRepository repo;

    public AdvisingConversationService(JdbcTemplate jdbc) {
        this.repo = new AdvisingConversationRepository(jdbc);
    }

    public List<Request> list(UUID actorId, boolean advisor) {
        return list(actorId, advisor, null, null, "ALL");
    }

    public List<Request> list(UUID actorId, boolean advisor, String statusFilter, String search) {
        return list(actorId, advisor, statusFilter, search, "ALL");
    }

    /** An advisor sees the whole pool, own major group first; scope MINE keeps the threads they joined. */
    public List<Request> list(UUID actorId, boolean advisor, String statusFilter, String search, String scope) {
        boolean mineOnly = advisor && scope != null && "MINE".equalsIgnoreCase(scope.strip());
        Comparator<Request> order = advisor
                ? Comparator.comparing(Request::sameMajorGroup).reversed().thenComparing(NEWEST_FIRST)
                : NEWEST_FIRST;
        return repo.findAll(actorId, advisor, repo.viewer(actorId, advisor)).stream()
                .filter(req -> !mineOnly || req.participatedByMe())
                .filter(req -> matchesStatus(req, statusFilter))
                .filter(req -> matchesSearch(req, search))
                .sorted(order)
                .toList();
    }

    public Page listPaged(UUID actorId, boolean advisor, String statusFilter, String search, int page, int size) {
        return listPaged(actorId, advisor, statusFilter, search, "ALL", page, size);
    }

    public Page listPaged(UUID actorId, boolean advisor, String statusFilter, String search, String scope,
            int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid page or size");
        }
        List<Request> filtered = list(actorId, advisor, statusFilter, search, scope);
        long total = filtered.size();
        int fromIdx = Math.min(page * size, filtered.size());
        int toIdx = Math.min(fromIdx + size, filtered.size());
        List<Request> items = filtered.subList(fromIdx, toIdx);
        long totalPages = (total + size - 1) / size;
        return new Page(items, page, size, total, totalPages);
    }

    public Detail detail(UUID actorId, boolean advisor, UUID requestId) {
        Request request = find(actorId, advisor, requestId);
        return new Detail(request, repo.findMessages(requestId, advisor));
    }

    @Transactional
    public Detail addMessage(UUID actorId, boolean advisor, UUID requestId, String content, boolean internal) {
        if (content == null || content.isBlank() || content.length() > 10_000) {
            throw new IllegalArgumentException("Message must have 1 to 10000 characters");
        }
        repo.lockRequest(requestId);
        Request request = find(actorId, advisor, requestId);
        requireOpen(request);
        repo.insertMessage(requestId, actorId, content.strip(), advisor && internal);
        if (advisor && !internal) {
            repo.claimAndMarkInProgress(requestId, actorId);
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
        Request request = find(actorId, advisor, requestId);
        String normalized = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        validateStatusChange(advisor, normalized, summary);
        requireOpen(request);
        repo.updateStatus(requestId, advisor ? actorId : null, normalized, summary);
        if (advisor) {
            repo.notifyUser(request.studentId(), "Advising request updated", "Your advising request status changed.");
        } else if (request.advisorId() != null) {
            repo.notifyUser(request.advisorId(), "Advising request cancelled", "A student cancelled an advising request.");
        }
        return detail(actorId, advisor, requestId);
    }

    private Request find(UUID actorId, boolean advisor, UUID requestId) {
        return repo.findOne(actorId, advisor, requestId, repo.viewer(actorId, advisor));
    }

    private static void requireOpen(Request request) {
        if (CLOSED.contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is closed");
        }
    }

    /** Resolving or rejecting needs a summary; cancelling does not. */
    private static void validateStatusChange(boolean advisor, String status, String summary) {
        if (!advisor) {
            if (!"CANCELLED".equals(status)) {
                throw new IllegalArgumentException("Student can only cancel a request");
            }
            return;
        }
        if (!ADVISOR_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Advisor status must be IN_PROGRESS, RESOLVED, REJECTED, or CANCELLED");
        }
        if (("RESOLVED".equals(status) || "REJECTED".equals(status)) && (summary == null || summary.isBlank())) {
            throw new IllegalArgumentException("Resolution summary is required");
        }
        if (summary != null && summary.length() > 10_000) {
            throw new IllegalArgumentException("Resolution summary is too long");
        }
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
            String studentMajorCode, String studentMajorName, String studentMajorGroupId,
            String studentMajorGroupCode, String studentMajorGroupName, boolean sameMajorGroup,
            String advisorId, String advisorName, boolean participatedByMe, String studyPlanId,
            String linkedAppointmentId, String title, String description, String requestType,
            String status, String resolutionSummary, LocalDateTime resolvedAt,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        public Request(String id, String studentId, String studentName, String studentCode,
                String advisorId, String advisorName, String studyPlanId, String title,
                String description, String requestType, String status, String resolutionSummary,
                LocalDateTime resolvedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
            this(id, studentId, studentName, studentCode, null, null, null, null, null, false,
                    advisorId, advisorName, false, studyPlanId, null, title, description, requestType,
                    status, resolutionSummary, resolvedAt, createdAt, updatedAt);
        }
    }

    public record Message(String id, String senderId, String senderName, String senderRole,
            String content, boolean internal, LocalDateTime createdAt) {
        public Message(String id, String senderId, String senderName, String content,
                boolean internal, LocalDateTime createdAt) {
            this(id, senderId, senderName, null, content, internal, createdAt);
        }
    }

    public record Detail(Request request, List<Message> messages) {}

    public record Page(List<Request> items, int page, int size, long totalElements, long totalPages) {}

    public record Attachment(String id, String fileName, String url, long size, LocalDateTime uploadedAt) {}
}
