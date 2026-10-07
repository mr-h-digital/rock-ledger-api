package za.co.rockmission.ledger.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import za.co.rockmission.ledger.auth.SecretBox;

/**
 * Roles:
 *   ADMIN     everything, including users
 *   TREASURER capture, import and post (no user management)
 *   VIEWER    read-only; cannot see imported bank statements
 */
@Configuration
public class SecurityConfig {

    private static String requireSecret(String s) {
        if (s == null || s.length() < 32) {
            throw new IllegalStateException("Set APP_SECRET to a random string of at least 32 characters.");
        }
        return s;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecretBox secretBox(@Value("${ledger.secret:}") String secret) {
        return new SecretBox(SecretBox.derive(requireSecret(secret), "totp-encryption"));
    }

    private static SecretKey jwtKey(String secret) {
        return new SecretKeySpec(SecretBox.derive(requireSecret(secret), "jwt-signing"), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${ledger.secret:}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey(secret)));
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${ledger.secret:}") String secret) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(jwtKey(secret)).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer("rock-ledger"));
        return d;
    }

    /** Only "access" tokens carry roles; a "pending" login-step token authenticates but is authorised for nothing. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (!"access".equals(jwt.getClaimAsString("tt"))) return List.<GrantedAuthority>of();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.<GrantedAuthority>of();
            return roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        });
        return c;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable) // bearer-token API; the one cookie endpoint also requires X-Requested-With
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                .requestMatchers("/api/auth/**").authenticated()
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/bank-statements/**", "/api/bank-lines/**").hasAnyRole("ADMIN", "TREASURER")
                .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "TREASURER", "VIEWER")
                .anyRequest().hasAnyRole("ADMIN", "TREASURER"))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${ledger.allowed-origin}") String origin) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
