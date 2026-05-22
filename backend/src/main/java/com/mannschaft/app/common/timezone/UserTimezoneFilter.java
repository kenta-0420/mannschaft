package com.mannschaft.app.common.timezone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneId;

/**
 * ユーザーの timezone を各リクエストの {@link TimezoneContextHolder} にセットするフィルター。
 *
 * <p>実行順序:</p>
 * <p>Spring Security の DelegatingFilterProxy（@Order = -100）より後に実行されるため、
 * JwtAuthenticationFilter が SecurityContextHolder にセット済みの状態で動作する。
 * {@link com.mannschaft.app.common.i18n.UserLocaleFilter}（LOWEST_PRECEDENCE - 10）と同じ優先度帯で、
 * ロケールフィルターの直後（LOWEST_PRECEDENCE - 9）に実行させる。</p>
 *
 * <p>timezone 決定ロジック:</p>
 * <ol>
 *   <li>ログイン済み（SecurityContext に Authentication あり）
 *       → {@link UserTimezoneCache#getTimezone(Long)} でキャッシュ参照（TTL 5分）
 *       → 不正な timezone 文字列は catch して "Asia/Tokyo" にフォールバック</li>
 *   <li>未ログイン、またはキャッシュ未利用時（@WebMvcTest スライス等）→ UTC</li>
 * </ol>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 9)
public class UserTimezoneFilter extends OncePerRequestFilter {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    /** @WebMvcTest スライスではキャッシュ Bean が存在しないため required = false で注入する */
    @Autowired(required = false)
    private UserTimezoneCache userTimezoneCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            ZoneId zoneId = resolveZoneId();
            TimezoneContextHolder.set(zoneId);
            filterChain.doFilter(request, response);
        } finally {
            // スレッドプール汚染防止: 必ず finally でクリアする
            TimezoneContextHolder.clear();
        }
    }

    private ZoneId resolveZoneId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // ログイン済みの場合は DB（キャッシュ経由）から timezone を取得
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String principal) {
            try {
                Long userId = Long.parseLong(principal);
                String timezoneStr = userTimezoneCache.getTimezone(userId);
                return parseZoneId(timezoneStr);
            } catch (NumberFormatException e) {
                log.debug("userId のパース失敗: {}", auth.getPrincipal());
            }
        }

        // 未ログイン: TimezoneContextHolder.get() が UTC を返すため、ここでは UTC を明示セット
        return ZoneId.of("UTC");
    }

    /**
     * タイムゾーン文字列を ZoneId に変換する。
     * 不正な文字列の場合は Asia/Tokyo にフォールバックする。
     *
     * @param timezoneStr タイムゾーン文字列（例: "Asia/Tokyo"）
     * @return ZoneId
     */
    private ZoneId parseZoneId(String timezoneStr) {
        if (timezoneStr == null || timezoneStr.isBlank()) {
            return SERVER_ZONE;
        }
        try {
            return ZoneId.of(timezoneStr);
        } catch (Exception e) {
            log.warn("不正なタイムゾーン文字列: {} → Asia/Tokyo にフォールバック", timezoneStr);
            return SERVER_ZONE;
        }
    }
}
