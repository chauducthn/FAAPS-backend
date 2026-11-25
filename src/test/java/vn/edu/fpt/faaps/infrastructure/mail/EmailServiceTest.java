package vn.edu.fpt.faaps.infrastructure.mail;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class EmailServiceTest {
    @Test
    void sendsHtmlPasswordResetEmailWhenEnabled() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);

        EmailService service = new EmailService(sender, true, "smtp.gmail.com",
                "faaps.test@gmail.com", "FAAPS Support");
        service.sendPasswordResetLink("student@fpt.edu.vn", "<b>Minh</b>",
                "http://localhost:5174/reset-password?token=abc123", 30);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        String body = (String) captor.getValue().getContent();
        assertTrue(body.contains("&lt;b&gt;Minh&lt;/b&gt;"));
        assertTrue(body.contains("http://localhost:5174/reset-password?token=abc123"));
        assertTrue(body.contains("30"));
    }

    @Test
    void sendsAdminResetPasswordEmailWhenEnabled() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);

        EmailService service = new EmailService(sender, true, "mailpit",
                "no-reply@faaps.local", "FAAPS Support");
        service.sendAdminResetPassword("student@fpt.edu.vn", "Nguyễn Văn A", "Faaps@123456");

        verify(sender).send(mime);
        String body = (String) mime.getContent();
        assertTrue(body.contains("Faaps@123456"));
    }

    @Test
    void skipsDeliveryWhenDisabledOrHostMissing() {
        JavaMailSender sender = mock(JavaMailSender.class);

        new EmailService(sender, false, "smtp.gmail.com", "a@gmail.com", "FAAPS")
                .sendPasswordResetLink("student@fpt.edu.vn", "A", "http://localhost/reset", 30);
        new EmailService(sender, true, "", "a@gmail.com", "FAAPS")
                .sendPasswordResetLink("student@fpt.edu.vn", "A", "http://localhost/reset", 30);

        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void swallowsMailExceptionsSoCallerFlowDoesNotFail() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        doThrow(new MailSendException("SMTP down")).when(sender).send(any(MimeMessage.class));

        EmailService service = new EmailService(sender, true, "smtp.gmail.com",
                "a@gmail.com", "FAAPS");
        assertDoesNotThrow(() -> service.sendAdminResetPassword("student@fpt.edu.vn", "A", "Faaps@999999"));
    }
}
