package com.mannschaft.app.common;

import com.mannschaft.app.common.util.SessionHashUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

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
     * 現在のセッションの session_hash（SHA-256(access_token_jti)）を取得する。
     * JwtAuthenticationFilter が details に格納した jti をハッシュ化して返す。
     * 未認証・jti 未設定の場合は null を返す。
     *
     * @return session_hash（64文字の16進数文字列）または null
     */
    public static String getCurrentSessionHash() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object details = auth.getDetails();
        if (details instanceof Map<?, ?> map) {
            Object jti = map.get("jti");
            if (jti instanceof String s && !s.isBlank()) {
                return SessionHashUtil.hash(s);
            }
        }
        return null;
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
