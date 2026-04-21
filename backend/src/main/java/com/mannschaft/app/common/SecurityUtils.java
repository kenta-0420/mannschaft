package com.mannschaft.app.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContextHolder からログインユーザー情報を取得するユーティリティ。
 * JwtAuthenticationFilter が設定した Authentication の principal（userId 文字列）を返す。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 現在の認証済みユーザーIDを取得する。
     *
     * @return ユーザーID
     * @throws BusinessException 未認証の場合
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        return Long.valueOf(authentication.getName());
    }

    /**
     * 現在のユーザーIDを取得する。未認証の場合は null を返す。
     *
     * @return ユーザーID、未認証の場合は null
     */
    public static Long getCurrentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
