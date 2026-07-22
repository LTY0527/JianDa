package cn.jianda.security;

import cn.jianda.common.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;

public final class UserContext {
    private UserContext() {
    }

    public static AuthUser current() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthUser user) {
            return user;
        }
        throw new BusinessException(401, "请先登录");
    }
}

