package cn.jianda.log;

import cn.jianda.common.ApiResponse;
import cn.jianda.security.AuthUser;
import cn.jianda.security.UserContext;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operation-logs")
public class OperationLogController {
    private final JdbcTemplate jdbc;

    public OperationLogController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        AuthUser user = UserContext.current();
        String sql = "SELECT l.*,u.display_name operator_name FROM operation_log l JOIN staff_user u ON u.id=l.operator_id ";
        return ApiResponse.ok(user.isPlatformAdmin() ? jdbc.queryForList(sql + "ORDER BY l.created_at DESC LIMIT 200")
                : jdbc.queryForList(sql + "WHERE l.organization_id=? ORDER BY l.created_at DESC LIMIT 200", user.organizationId()));
    }
}

