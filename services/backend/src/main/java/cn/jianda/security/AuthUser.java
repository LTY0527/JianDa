package cn.jianda.security;

public record AuthUser(long id, long organizationId, String username, String displayName, String role,
                       String organizationName) {
    public boolean isPlatformAdmin() {
        return "PLATFORM_ADMIN".equals(role);
    }
}

