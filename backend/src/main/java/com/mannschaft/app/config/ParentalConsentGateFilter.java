package com.mannschaft.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意待ち（{@code PENDING_PARENTAL_CONSENT}）ユーザーの
 * 保護 API アクセスをサーバ側で遮断するフィルター。
 *
 * <p>従来は「18歳未満は保護者同意完了まで利用不可」がフロントのリダイレクトのみで、バックエンドは
 * 同意待ちユーザーにもトークンを発行し API を通していた（専用エラーコード {@code AUTH_070} は
 * 文言のみで未配線）。本フィルターがサーバ側でこれを強制する。</p>
 *
 * <p><b>方式</b>: {@link JwtAuthenticationFilter} の直後（{@code addFilterAfter}）に差し、
 * 認証済み principal かつ details の {@code ppc==true} のときだけ発火する。リクエストのパス/メソッドが
 * 許可リストに該当しなければ 403 {@code AUTH_070} を返す。ログイン自体は許す（連携申請の導線が要る）。</p>
 *
 * <p><b>401 は絶対に返さない</b>（403 Forbidden 固定）。フロントの {@code useApi.ts} の 401 ハンドラが
 * 無限リフレッシュを起こすため。本フィルターは {@code @ControllerAdvice} の外なので
 * {@link com.mannschaft.app.common.BusinessException} では拾われず、
 * 既存 {@code accessDeniedHandler} と同スキーマの手書き JSON を直接書き込む。</p>
 *
 * <p><b>許可リスト</b>:</p>
 * <ul>
 *   <li>{@code /api/v1/parental-consent/**} — 同意管理専用 API（招待・保護者一覧・承認/否認）は丸ごと許可</li>
 *   <li>{@code GET /api/v1/users/me} — 本人状態確認のみ。退会（DELETE）・プロフィール編集（PATCH）は許可しない</li>
 *   <li>{@code /api/v1/auth/**} — 認証ライフサイクル（ログアウト・リフレッシュ・2FA 等）。多重防御</li>
 *   <li>{@code /api/i18n/**}, {@code /api/v1/public/**} — permitAll 系プレフィックス。多重防御</li>
 * </ul>
 */
@Component
@Slf4j
public class ParentalConsentGateFilter extends OncePerRequestFilter {

    /**
     * 既存 {@code accessDeniedHandler}（SecurityConfig）と同スキーマの手書き 403 ボディ。
     * {@code {"error":{"code":"AUTH_070","message":<非空>,"fieldErrors":[]}}}。
     */
    private static final String AUTH_070_BODY =
            "{\"error\":{\"code\":\"AUTH_070\","
            + "\"message\":\"保護者の同意が完了するまで、この操作は利用できません\","
            + "\"fieldErrors\":[]}}";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (isPendingParentalConsent() && !isAllowed(request)) {
            // 401 ではなく 403 固定（useApi.ts の 401 → 無限リフレッシュ回避）。
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(AUTH_070_BODY);
            log.debug("保護者同意待ちユーザーの保護 API アクセスを遮断: {} {}",
                    request.getMethod(), request.getServletPath());
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 現在の認証主体が保護者同意待ち（ppc==true）かどうかを判定する。
     * {@link com.mannschaft.app.common.SecurityUtils#getCurrentSessionHash()} の
     * details Map 読み出しパターンに準拠する。未認証・匿名は false（フィルター無発火）。
     */
    private boolean isPendingParentalConsent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return false;
        }
        Object details = auth.getDetails();
        if (details instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("ppc"));
        }
        return false;
    }

    /**
     * リクエストが保護者同意待ちユーザーに許可された経路かを判定する。
     *
     * @param request HTTP リクエスト
     * @return 許可された経路なら true
     */
    private boolean isAllowed(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) {
            return false;
        }
        String method = request.getMethod();

        // 保護者同意管理 API は丸ごと許可（子ユーザーの同意取得導線・配下に無害）。
        if (path.equals("/api/v1/parental-consent")
                || path.startsWith("/api/v1/parental-consent/")) {
            return true;
        }
        // 本人状態確認のみ許可（GET 限定）。退会 DELETE / プロフィール編集 PATCH は遮断する。
        if (HttpMethod.GET.matches(method) && path.equals("/api/v1/users/me")) {
            return true;
        }
        // 認証ライフサイクル（ログアウト・リフレッシュ・2FA・WebAuthn・OAuth 等）は多重防御で許可。
        if (path.startsWith("/api/v1/auth/")) {
            return true;
        }
        // permitAll 系プレフィックス（多重防御。ゲートは認証済み ppc ユーザーのみ発火するため実質冗長）。
        if (path.startsWith("/api/i18n/")
                || path.startsWith("/api/v1/public/")) {
            return true;
        }
        return false;
    }
}
