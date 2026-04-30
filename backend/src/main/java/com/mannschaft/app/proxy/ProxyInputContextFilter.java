package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 代理入力コンテキストフィルター（F14.1）。
 * X-Proxy-For-User-Id ヘッダーが存在する場合のみ動作し、DB再検証後に
 * ProxyInputContext をアクティブ化する。JwtAuthenticationFilter の直後に実行される。
 *
 * ヘッダーがない場合は通常入力として何もせず chain を続行する。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyInputContextFilter extends OncePerRequestFilter {

    static final String HEADER_PROXY_FOR = "X-Proxy-For-User-Id";
    static final String HEADER_PROXY_CONSENT = "X-Proxy-Consent-Id";
    static final String HEADER_PROXY_SOURCE = "X-Proxy-Input-Source";
    static final String HEADER_PROXY_STORAGE = "X-Proxy-Original-Storage";

    private final ProxyInputConsentRepository proxyInputConsentRepository;
    private final ProxyInputContext proxyInputContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String proxyForHeader = request.getHeader(HEADER_PROXY_FOR);

        // ヘッダーがない場合は通常入力として続行
        if (proxyForHeader == null) {
            chain.doFilter(request, response);
            return;
        }

        // 必須ヘッダーの存在チェック
        String consentIdHeader = request.getHeader(HEADER_PROXY_CONSENT);
        String inputSourceHeader = request.getHeader(HEADER_PROXY_SOURCE);
        String storageHeader = request.getHeader(HEADER_PROXY_STORAGE);

        if (consentIdHeader == null || inputSourceHeader == null || storageHeader == null
                || storageHeader.isBlank()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "代理入力ヘッダーが不完全です。X-Proxy-Consent-Id, X-Proxy-Input-Source, X-Proxy-Original-Storage は必須です。");
            return;
        }

        // 数値パース
        Long subjectUserId;
        Long consentId;
        try {
            subjectUserId = Long.parseLong(proxyForHeader.trim());
            consentId = Long.parseLong(consentIdHeader.trim());
        } catch (NumberFormatException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "X-Proxy-For-User-Id または X-Proxy-Consent-Id の形式が不正です。");
            return;
        }

        // InputSource バリデーション
        try {
            ProxyInputRecordEntity.InputSource.valueOf(inputSourceHeader.trim());
        } catch (IllegalArgumentException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "X-Proxy-Input-Source の値が不正です。PAPER_FORM / PHONE_INTERVIEW / IN_PERSON のいずれかを指定してください。");
            return;
        }

        // SecurityContext からリクエスト者（代理者）のIDを取得
        Long proxyUserId = extractCurrentUserId();
        if (proxyUserId == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "代理入力を行うには認証が必要です。");
            return;
        }

        // DB再検証: 有効な同意書が存在し、proxyUserIdが一致するか
        Optional<ProxyInputConsentEntity> consentOpt =
                proxyInputConsentRepository.findValidConsent(consentId, proxyUserId);

        if (consentOpt.isEmpty()) {
            log.warn("代理入力の同意書検証失敗: consentId={}, proxyUserId={}", consentId, proxyUserId);
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "有効な代理入力の同意書が見つかりません。同意書が承認済みかつ有効期限内であることを確認してください。");
            return;
        }

        ProxyInputConsentEntity consent = consentOpt.get();

        // subjectUserIdの一致確認
        if (!consent.getSubjectUserId().equals(subjectUserId)) {
            log.warn("代理入力の対象ユーザー不一致: requested={}, consent={}", subjectUserId, consent.getSubjectUserId());
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "指定された代理対象ユーザーと同意書の内容が一致しません。");
            return;
        }

        // 検証OK: ProxyInputContext をアクティブ化
        proxyInputContext.activate(subjectUserId, consentId, inputSourceHeader.trim(), storageHeader.trim());
        log.debug("代理入力モード有効化: proxyUserId={}, subjectUserId={}, consentId={}",
                proxyUserId, subjectUserId, consentId);

        chain.doFilter(request, response);
    }

    private Long extractCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return null;
        }
        try {
            return Long.parseLong(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String escaped = message.replace("\"", "\\\"");
        response.getWriter().write("{\"error\":\"" + escaped + "\"}");
    }
}
