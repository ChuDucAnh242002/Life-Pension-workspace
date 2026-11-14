package fi.adviser.workspace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    UserDetailsService users(@Value("${app.adviser-user}") String username,
                             @Value("${app.adviser-password}") String password) {
        if (username.isBlank() || username.length() > 100 || password.length() < 12 || password.length() > 72) {
            throw new IllegalArgumentException("Configure an adviser username (1-100 characters) and password (12-72 characters).");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("ADVISER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, @Value("${app.public-demo:false}") boolean publicDemo,
                                 @Value("${app.policy.transport:demo}") String policyTransport,
                                 @Value("${app.policy.simulator:true}") boolean policySimulator,
                                 @Value("${app.ai.enabled:false}") boolean aiEnabled,
                                 @Value("${app.public-demo-external-integrations:false}") boolean publicIntegrations) throws Exception {
        validatePublicDemo(publicDemo, policyTransport, policySimulator, aiEnabled, publicIntegrations);
        if (publicDemo) {
            http.addFilterBefore(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    if (SecurityContextHolder.getContext().getAuthentication() == null) {
                        SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated("public-demo", null,
                                        AuthorityUtils.createAuthorityList("ROLE_ADVISER")));
                    }
                    chain.doFilter(request, response);
                }
            }, AnonymousAuthenticationFilter.class);
        }
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/api/csrf", "/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> response.sendError(401, "Sign in to continue.")))
                .formLogin(login -> login.loginProcessingUrl("/api/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> response.sendError(401, "Sign-in failed."))
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .build();
    }

    static void validatePublicDemo(boolean publicDemo, String policyTransport, boolean policySimulator,
                                   boolean aiEnabled, boolean publicIntegrations) {
        if (publicDemo && !policyTransport.equals("demo")
                && !(publicIntegrations && policyTransport.equals("mq") && policySimulator)) {
            throw new IllegalStateException("Public demo access requires demo policy transport or an explicitly enabled MQ simulator.");
        }
        if (publicDemo && aiEnabled && !publicIntegrations) {
            throw new IllegalStateException("Public demo AI requires explicit external integrations opt-in.");
        }
    }
}
