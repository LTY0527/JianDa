package cn.jianda.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration expiration;

    public JwtService(@Value("${jianda.jwt-secret}") String secret,
                      @Value("${jianda.jwt-expiration-hours:12}") long expirationHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofHours(expirationHours);
    }

    public String issue(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder().subject(user.username()).claim("uid", user.id()).claim("oid", user.organizationId())
                .claim("name", user.displayName()).claim("role", user.role()).claim("org", user.organizationName())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(expiration))).signWith(key).compact();
    }

    public AuthUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthUser(((Number) claims.get("uid")).longValue(), ((Number) claims.get("oid")).longValue(),
                claims.getSubject(), claims.get("name", String.class), claims.get("role", String.class),
                claims.get("org", String.class));
    }
}

