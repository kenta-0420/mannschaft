package com.mannschaft.app.common.i18n;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ユーザーの locale を各リクエストの LocaleContextHolder にセットするフィルター。
 *
 * 実行順序:
 * Spring Security の DelegatingFilterProxy（@Order = -100）より後に実行されるため、
 * JwtAuthenticationFilter が SecurityContextHolder にセット済みの状態で動作する。
 * @Order(LOWEST_PRECEDENCE - 10) = Integer.MAX_VALUE - 10 → Security より大きい数 → 後続実行
 *
 * locale 決定ロジック:
 * 1. ログイン済み（SecurityContext に Authentication あり）
 *    → UserLocaleCache.getLocale(userId) でキャッシュ参照（TTL 5分）
 *    → null の場合 "ja" にフォールバック
 * 2. 未ログイン
 *    → Accept-Language ヘッダーを Locale.LanguageRange.parse() で解析
 *    → 不正ヘッダーは IllegalArgumentException → DEFAULT_LOCALE にフォールバック
 * 3. いずれも SUPPORTED_LOCALES に含まれない場合 → DEFAULT_LOCALE
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@RequiredArgsConstructor
public class UserLocaleFilter extends OncePerRequestFilter {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("ja", "en", "zh", "ko", "es", "de");
    private static final Locale DEFAULT_LOCALE = Locale.JAPANESE;

    private final UserLocaleCache userLocaleCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Locale locale = resolveLocale(request);
            // inheritable=false 固定: Virtual Threads 環境で InheritableThreadLocal への伝搬を防ぐ
            org.springframework.context.i18n.LocaleContextHolder
                    .setLocale(locale, false);
            filterChain.doFilter(request, response);
        } finally {
            // スレッドプール汚染防止: 必ず finally でクリアする
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    private Locale resolveLocale(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // ログイン済みの場合は DB（キャッシュ経由）から locale を取得
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String principal) {
            try {
                Long userId = Long.parseLong(principal);
                String localeStr = userLocaleCache.getLocale(userId);
                // null ガード + サブタグ除去 (例: "en-US" → "en")
                if (localeStr != null) {
                    String normalized = Locale.forLanguageTag(localeStr).getLanguage();
                    if (SUPPORTED_LOCALES.contains(normalized)) {
                        return Locale.forLanguageTag(normalized);
                    }
                }
            } catch (NumberFormatException e) {
                log.debug("userId のパース失敗: {}", auth.getPrincipal());
            }
            return DEFAULT_LOCALE;
        }

        // 未ログイン: Accept-Language ヘッダーから決定
        return resolveFromAcceptLanguage(request);
    }

    private Locale resolveFromAcceptLanguage(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return DEFAULT_LOCALE;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            for (Locale.LanguageRange range : ranges) {
                // ベース言語コードのみ抽出（例: "en-US" → "en"）
                String lang = Locale.forLanguageTag(range.getRange()).getLanguage();
                if (SUPPORTED_LOCALES.contains(lang)) {
                    return Locale.forLanguageTag(lang);
                }
            }
        } catch (IllegalArgumentException e) {
            // 不正な Accept-Language ヘッダー → デフォルトにフォールバック
            log.debug("不正な Accept-Language ヘッダー: {}", header);
        }
        return DEFAULT_LOCALE;
    }
}
