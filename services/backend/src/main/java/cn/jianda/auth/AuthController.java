package cn.jianda.auth;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import cn.jianda.security.JwtService;
import cn.jianda.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        var users = jdbc.query("SELECT u.id,u.organization_id,u.username,u.password_hash,u.display_name,u.role,o.name organization_name "
                        + "FROM staff_user u JOIN organization o ON o.id=u.organization_id WHERE u.username=? AND u.status='ACTIVE'",
                (rs, rowNum) -> Map.<String, Object>of("id", rs.getLong("id"), "organizationId", rs.getLong("organization_id"),
                        "username", rs.getString("username"), "passwordHash", rs.getString("password_hash"),
                        "displayName", rs.getString("display_name"), "role", rs.getString("role"),
                        "organizationName", rs.getString("organization_name")), request.username());
        if (users.isEmpty() || !passwordEncoder.matches(request.password(), users.get(0).get("passwordHash").toString())) {
            throw new BusinessException(401, "账号或密码错误");
        }
        Map<String, Object> row = users.get(0);
        AuthUser user = new AuthUser(((Number) row.get("id")).longValue(), ((Number) row.get("organizationId")).longValue(),
                row.get("username").toString(), row.get("displayName").toString(), row.get("role").toString(),
                row.get("organizationName").toString());
        return ApiResponse.ok(Map.of("token", jwtService.issue(user), "user", user));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUser> me() {
        return ApiResponse.ok(UserContext.current());
    }

    public record LoginRequest(@NotBlank(message = "请输入账号") String username,
                               @NotBlank(message = "请输入密码") String password) {
    }
}

