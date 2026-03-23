package com.mannschaft.app.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * クライアントIPアドレス取得ユーティリティ。
 * リバースプロキシ（CloudFront, ALB, nginx 等）経由のリクエストに対応する。
 */
public final class IpAddressUtils {

    private IpAddressUtils() {}

    /**
     * X-Forwarded-For ヘッダーを考慮してクライアントIPアドレスを取得する。
     * 複数プロキシ経由の場合、最左（オリジナルクライアント）のIPを返す。
     */
    public static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 複数IP（client, proxy1, proxy2）の場合、最初のIPがクライアント
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
