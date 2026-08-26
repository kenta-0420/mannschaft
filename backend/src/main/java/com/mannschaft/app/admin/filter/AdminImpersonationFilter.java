package com.mannschaft.app.admin.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 管理者変身フィルター（F10.1）。
 *
 * <p>SYSTEM_ADMIN ユーザーが {@code X-Admin-Impersonate-User-Id} ヘッダーを送信した場合、
 * SecurityContext の principal を対象ユーザー ID に置き換える。
 * 権限（ROLE_SYSTEM_ADMIN を含む）は元の管理者のものをそのまま引き継ぐ。</p>
 *
 * <p>admin / system-admin エンドポイントはこのフィルターをスキップするため、
 * 変身中であっても管理者操作（回答・ステータス変更など）は元の管理者権限で動く。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminImpersonationFilter extends OncePerRequestFilter {

    /** 変身先ユーザー ID を指定するリクエストヘッダー名。 */
    public static final String HEADER_IMPERSONATE = "X-Admin-Impersonate-User-Id";

    /** リクエスト属性に保存する元の管理者 ID のキー。 */
    static final String ATTR_ORIGINAL_ADMIN_ID = "originalAdminId";

    private final ObjectMapper objectMapper;

    /**
     * admin / system-admin エンドポイントは変身フィルターをスキップする。
     * 管理者向け API では元の管理者 ID が必要なため。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/admin/") || path.startsWith("/api/v1/system-admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER_IMPERSONATE);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        boolean isSystemAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SYSTEM_ADMIN".equals(a.getAuthority()));
        if (!isSystemAdmin) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "変身機能はシステム管理者のみ使用できます");
            return;
        }

        Long targetUserId;
        try {
            targetUserId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "X-Admin-Impersonate-User-Id の形式が不正です");
            return;
        }

        // 元の管理者 ID をリクエスト属性に保存（監査ログ等で参照可能にする）
        request.setAttribute(ATTR_ORIGINAL_ADMIN_ID, auth.getName());

        // SecurityContext の principal を対象ユーザー ID に置き換え
        // 権限は元の管理者のものを引き継ぐため、admin エンドポイントへのアクセスも継続可能
        UsernamePasswordAuthenticationToken impersonationAuth =
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(targetUserId),
                        auth.getCredentials(),
                        auth.getAuthorities()
                );
        SecurityContextHolder.getContext().setAuthentication(impersonationAuth);

        log.info("管理者変身: adminId={}, targetUserId={}, path={}",
                auth.getName(), targetUserId, request.getRequestURI());

        chain.doFilter(request, response);
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", message)));
    }
}
