package fi.adviser.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

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
    SecurityFilterChain security(HttpSecurity http) throws Exception {
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
}
