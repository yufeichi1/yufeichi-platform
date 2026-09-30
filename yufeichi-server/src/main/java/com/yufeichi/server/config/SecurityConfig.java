package com.yufeichi.server.config;

import com.yufeichi.server.security.JwtAuthenticationFilter;
import com.yufeichi.server.security.RestAccessDeniedHandler;
import com.yufeichi.server.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final org.springframework.core.env.Environment environment;

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration() {
        // Run only inside Spring Security, not a second time as a servlet filter.
        var registration = new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .exceptionHandling(exception ->
                        exception
                                .authenticationEntryPoint(
                                        authenticationEntryPoint
                                )
                                .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(authorize ->
                        authorize
                                // Async completion is dispatched by the container after the REQUEST
                                // has passed JWT and method permissions; don't authenticate it again.
                                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                                .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                    !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))))
                                .requestMatchers(
                                        "/api/auth/login",
                                        "/api/health",
                                        "/error",
                                        "/doc.html",
                                        "/webjars/**",
                                        "/v3/api-docs/**",
                                        "/swagger-ui/**",
                                        "/swagger-ui.html",
                                        "/favicon.ico"
                                )
                                .permitAll()

                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/api/articles/**",
                                        "/api/projects/**",
                                        "/api/categories/**",
                                        "/api/tags/**",
                                        "/uploads/**"
                                )
                                .permitAll()

                                .anyRequest()
                                .authenticated()
                )
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOriginPatterns(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))
                ? List.of("https://yufeichi.com", "https://www.yufeichi.com") : List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://yufeichi.com",
                "https://www.yufeichi.com"
        ));

        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(
                List.of("Authorization")
        );
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
