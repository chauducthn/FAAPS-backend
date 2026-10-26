package vn.edu.fpt.faaps.auth.security;

import vn.edu.fpt.faaps.auth.exception.AuthUnavailableException;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;
import vn.edu.fpt.faaps.auth.service.AuthRefreshTokenService;
import vn.edu.fpt.faaps.auth.service.AuthService;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;
    private final AuthRefreshTokenService refreshTokens;
    private final String frontendUrl;

    public GoogleLoginSuccessHandler(AuthService authService,
            SecurityContextRepository securityContextRepository,
            AuthRefreshTokenService refreshTokens,
            @Value("${faaps.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.authService = authService;
        this.securityContextRepository = securityContextRepository;
        this.refreshTokens = refreshTokens;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (!(authentication.getPrincipal() instanceof OidcUser googleUser)
                || !Boolean.TRUE.equals(googleUser.getEmailVerified())) {
            SecurityContextHolder.clearContext();
            response.sendRedirect(frontendUrl + "/login?error=google-email-unverified");
            return;
        }
        try {
            GoogleIdentity identity = new GoogleIdentity(googleUser.getSubject(), googleUser.getEmail(),
                    googleUser.getFullName());
            AuthSession session = authService.loginGoogle(identity);
            request.changeSessionId();
            refreshTokens.issueCookie(session.id(), request, response);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authService.authentication(session));
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            response.sendRedirect(frontendUrl + "/auth/callback");
        } catch (DisabledException exception) {
            SecurityContextHolder.clearContext();
            response.sendRedirect(frontendUrl + "/login?error=account-disabled");
        } catch (BadCredentialsException exception) {
            SecurityContextHolder.clearContext();
            response.sendRedirect(frontendUrl + "/login?error=google-account-conflict");
        } catch (AuthUnavailableException exception) {
            SecurityContextHolder.clearContext();
            response.sendRedirect(frontendUrl + "/login?error=account-store-unavailable");
        }
    }
}
