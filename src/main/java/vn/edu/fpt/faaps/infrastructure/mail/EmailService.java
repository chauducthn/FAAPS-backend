package vn.edu.fpt.faaps.infrastructure.mail;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String mailHost;
    private final String fromAddress;
    private final String fromName;

    @Autowired
    public EmailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${faaps.mail.enabled:false}") boolean enabled,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${faaps.mail.from:}") String fromAddress,
            @Value("${spring.mail.username:}") String smtpUsername,
            @Value("${faaps.mail.from-name:FAAPS Academic Advising}") String fromName) {
        this(mailSenderProvider.getIfAvailable(), enabled, mailHost,
                resolveFrom(fromAddress, smtpUsername), fromName);
    }

    public EmailService(JavaMailSender mailSender, boolean enabled, String mailHost,
            String fromAddress, String fromName) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.mailHost = mailHost == null ? "" : mailHost.strip();
        this.fromAddress = fromAddress == null || fromAddress.isBlank() ? "no-reply@faaps.local" : fromAddress.strip();
        this.fromName = fromName == null || fromName.isBlank() ? "FAAPS Academic Advising" : fromName.strip();
    }

    @Async
    public void sendPasswordResetLink(String toEmail, String fullName, String resetUrl, int ttlMinutes) {
        String safeName = HtmlUtils.htmlEscape(displayName(fullName));
        String safeUrl = HtmlUtils.htmlEscape(resetUrl);
        String subject = "[FAAPS] Đặt lại mật khẩu tài khoản";
        String html = """
                <div style="font-family:Arial,sans-serif;max-width:560px;margin:0 auto;color:#1f2937;line-height:1.6">
                  <h2 style="color:#f26f21;margin-bottom:12px">Yêu cầu đặt lại mật khẩu</h2>
                  <p>Xin chào <strong>%s</strong>,</p>
                  <p>Hệ thống FAAPS vừa nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.</p>
                  <p style="margin:24px 0">
                    <a href="%s" style="background:#f26f21;color:#ffffff;padding:12px 20px;border-radius:6px;text-decoration:none;font-weight:bold">
                      Đặt lại mật khẩu
                    </a>
                  </p>
                  <p>Hoặc sao chép đường dẫn sau vào trình duyệt:</p>
                  <p style="word-break:break-all;font-size:13px;color:#4b5563">%s</p>
                  <p style="font-size:13px;color:#6b7280">Liên kết này có hiệu lực trong <strong>%d phút</strong>. Nếu bạn không yêu cầu thao tác này, vui lòng bỏ qua email.</p>
                </div>
                """.formatted(safeName, safeUrl, safeUrl, ttlMinutes);
        sendHtmlEmail(toEmail, subject, html);
    }

    @Async
    public void sendAdminResetPassword(String toEmail, String fullName, String newPassword) {
        String safeName = HtmlUtils.htmlEscape(displayName(fullName));
        String safePassword = HtmlUtils.htmlEscape(newPassword);
        String subject = "[FAAPS] Mật khẩu tài khoản của bạn đã được cấp lại";
        String html = """
                <div style="font-family:Arial,sans-serif;max-width:560px;margin:0 auto;color:#1f2937;line-height:1.6">
                  <h2 style="color:#f26f21;margin-bottom:12px">Thông báo cấp lại mật khẩu</h2>
                  <p>Xin chào <strong>%s</strong>,</p>
                  <p>Quản trị viên hệ thống FAAPS vừa cấp lại mật khẩu mới cho tài khoản của bạn:</p>
                  <p style="margin:20px 0;padding:12px 16px;background:#fff7ed;border:1px solid #fed7aa;border-radius:6px;font-family:monospace;font-size:18px;font-weight:bold;color:#9a3412">
                    %s
                  </p>
                  <p style="font-size:13px;color:#6b7280">Vui lòng đăng nhập và đổi mật khẩu ngay sau lần đăng nhập đầu tiên để bảo mật tài khoản.</p>
                </div>
                """.formatted(safeName, safePassword);
        sendHtmlEmail(toEmail, subject, html);
    }

    @Async
    public void sendAdvisingMessage(
            String toEmail,
            String studentName,
            String advisorName,
            String subject,
            String messageBody) {
        String safeStudent = HtmlUtils.htmlEscape(displayName(studentName));
        String safeAdvisor = HtmlUtils.htmlEscape(displayName(advisorName));
        String safeBody = HtmlUtils.htmlEscape(messageBody == null ? "" : messageBody).replace("\n", "<br/>");
        String mailSubject = (subject == null || subject.isBlank())
                ? "[FAAPS] Academic Advising Notice"
                : "[FAAPS] " + subject.strip();
        String html = """
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#1f2937;line-height:1.6">
                  <h2 style="color:#f26f21;margin-bottom:12px">Academic Advising Message</h2>
                  <p>Hello <strong>%s</strong>,</p>
                  <p>Your academic advisor <strong>%s</strong> sent you the following advising note:</p>
                  <div style="margin:16px 0;padding:14px 16px;background:#f8fafc;border-left:4px solid #f26f21;border-radius:6px">
                    %s
                  </div>
                  <p style="font-size:13px;color:#6b7280">Please sign in to FAAPS to review your study plan or book a follow-up session.</p>
                </div>
                """.formatted(safeStudent, safeAdvisor, safeBody);
        sendHtmlEmail(toEmail, mailSubject, html);
    }

    public void sendHtmlEmail(String toEmail, String subject, String htmlBody) {
        if (toEmail == null || toEmail.isBlank()) {
            return;
        }
        if (!enabled || mailSender == null || mailHost.isBlank()) {
            log.info("Mail delivery skipped (enabled={}, hostConfigured={}) for recipient={} subject={}",
                    enabled, !mailHost.isBlank(), toEmail, subject);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, fromName);
            helper.setTo(toEmail.strip());
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent successfully to {} (subject={})", toEmail, subject);
        } catch (Exception ex) {
            log.warn("Failed to send email to {} (subject={}): {}", toEmail, subject, ex.getMessage());
        }
    }

    private static String resolveFrom(String fromAddress, String smtpUsername) {
        if (fromAddress != null && !fromAddress.isBlank()) {
            return fromAddress.strip();
        }
        if (smtpUsername != null && !smtpUsername.isBlank()) {
            return smtpUsername.strip();
        }
        return "no-reply@faaps.local";
    }

    private static String displayName(String fullName) {
        return fullName == null || fullName.isBlank() ? "bạn" : fullName.strip();
    }
}
