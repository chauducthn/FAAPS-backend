package vn.edu.fpt.faaps.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    @Bean
    public AuthService authService(ObjectProvider<AuthAccountStore> accountStores,
            ObjectProvider<DataSource> dataSources, PasswordEncoder passwordEncoder) {
        return new AuthService(accountStore(accountStores, dataSources),
                passwordEncoder);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            ObjectProvider<AuthAccountStore> accountStores,
            ObjectProvider<DataSource> dataSources,
            SecurityContextRepository securityContextRepository,
            AuthRefreshTokenService refreshTokens,
            GoogleLoginSuccessHandler googleLoginSuccessHandler,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            @Value("${faaps.frontend-url:http://localhost:5173}") String frontendUrl) throws Exception {
        http.securityContext(context -> context.securityContextRepository(securityContextRepository));
        http.cors(Customizer.withDefaults());
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/admin/login", "/api/auth/refresh",
                        "/oauth2/**", "/login/oauth2/**",
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/advisor/**").hasRole("ADVISOR")
                .requestMatchers("/api/student/**").hasRole("STUDENT")
                .anyRequest().authenticated());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication required"))
                .accessDeniedHandler((request, response, exception) ->
                        writeError(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access denied")));
        http.addFilterAfter(new AccountStatusFilter(
                accountStore(accountStores, dataSources)),
                UsernamePasswordAuthenticationFilter.class);
        http.logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .logoutSuccessHandler((request, response, authentication) -> {
                    refreshTokens.revokeFromCookie(request);
                    refreshTokens.clearCookie(response);
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                }));
        if (clientRegistrations.getIfAvailable() != null) {
            DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                    clientRegistrations.getIfAvailable(), "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(builder ->
                    builder.additionalParameters(parameters -> parameters.put("prompt", "select_account")));
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                    .successHandler(googleLoginSuccessHandler)
                    .failureHandler((request, response, exception) ->
                            response.sendRedirect(frontendUrl + "/login?error=google-sign-in-failed")));
        }
        return http.build();
    }

    private AuthAccountStore accountStore(ObjectProvider<AuthAccountStore> accountStores,
            ObjectProvider<DataSource> dataSources) {
        AuthAccountStore provided = accountStores.getIfAvailable();
        if (provided != null) {
            return provided;
        }
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null ? new UnavailableAuthAccountStore() : new JdbcAuthAccountStore(dataSource);
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
