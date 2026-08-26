package com.mannschaft.app.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
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

    /**
     * 後見切替（GUARDIANSHIP_SWITCH）で activate する際の {@code originalStorageLocation} 固定値。
     *
     * <p>後見切替は紙原本を伴わないオンライン代理だが、{@code proxy_input_records.original_storage_location}
     * は {@code NOT NULL}（V18.012）である。切替中に F14.1 代理入力 7 機能
     * （survey/出欠/shift/announcement 既読/parking/circulation 押印）が発火すると、
     * 各 Service の {@code buildAndSaveProxyInputRecord} が {@code context.getOriginalStorageLocation()} を
     * そのまま列に書くため、ここで {@code null} を渡すと NOT NULL 制約違反で 500 になる。
     * {@link com.mannschaft.app.auth.guardianship.GuardianshipSwitchService} の切替開始記録と
     * 同一文言の固定値を入れて整合させる（03_security §3.2 二重記録）。</p>
     */
    static final String SWITCH_STORAGE_LOCATION_NA = "N/A (online guardianship switch)";

    private final ProxyInputConsentRepository proxyInputConsentRepository;
    private final ProxyInputContext proxyInputContext;
    private final ObjectMapper objectMapper;

    /**
     * 後見切替の再検証サービス（F08.9 P3c）。
     *
     * <p>{@link ObjectProvider} による遅延解決とする。後見切替経路（{@code X-Proxy-For-User-Id} のみの
     * リクエスト）を踏んだときに初めて解決するため、本フィルタを組み立てる多数の
     * {@code @WebMvcTest} スライス（auth ドメインの Service を含まない構成・100件超）に
     * {@code @MockitoBean} 追加を強いない。また proxy→auth の構築時依存を解消する
     * （切替経路を使わない限り auth ドメイン Bean は不要）。</p>
     */
    private final ObjectProvider<GuardianshipSwitchService> guardianshipSwitchServiceProvider;

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

        String consentIdHeader = request.getHeader(HEADER_PROXY_CONSENT);

        // 後見切替経路（F08.9 P3c）: consent-id ヘッダなし＝紙同意書ベースでない acting-as。
        // 保護者リンク＋年齢ゲートを毎リクエスト再検証し、PAYMENT スコープのみで activate する。
        // consent-id があれば従来の F14.1 紙同意書経路へフォールスルー（既存経路を壊さない）。
        if (consentIdHeader == null) {
            handleGuardianshipSwitch(request, response, chain, proxyForHeader);
            return;
        }

        // 必須ヘッダーの存在チェック（従来の F14.1 紙同意書経路）
        String inputSourceHeader = request.getHeader(HEADER_PROXY_SOURCE);
        String storageHeader = request.getHeader(HEADER_PROXY_STORAGE);

        if (inputSourceHeader == null || storageHeader == null
                || storageHeader.isBlank()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "代理入力ヘッダーが不完全です。X-Proxy-Input-Source, X-Proxy-Original-Storage は必須です。");
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

        // 同意書で許可されたスコープ集合を抽出（F08.9 P3b: 決済系の要求スコープ検証の素地）
        java.util.Set<FeatureScope> grantedScopes = consent.getScopes().stream()
                .map(ProxyInputConsentScopeEntity::getFeatureScope)
                .collect(java.util.stream.Collectors.toSet());

        // 検証OK: ProxyInputContext をアクティブ化
        proxyInputContext.activate(subjectUserId, consentId, inputSourceHeader.trim(), storageHeader.trim(),
                grantedScopes);
        log.debug("代理入力モード有効化: proxyUserId={}, subjectUserId={}, consentId={}",
                proxyUserId, subjectUserId, consentId);

        chain.doFilter(request, response);
    }

    /**
     * 後見切替経路（F08.9 P3c）。{@code X-Proxy-For-User-Id} はあるが {@code X-Proxy-Consent-Id} が
     * ない場合に呼ばれる。保護者リンク＋年齢ゲートを毎リクエスト再検証し、合格時のみ
     * {@code PAYMENT} スコープのみで {@link ProxyInputContext#activate} する。
     *
     * <p>境界日跨ぎ（年度末・誕生日）の自動失効は本実行時ゲートで担保する（封印後の子へは AGE_LOCKED）。
     * inputSource は {@code GUARDIANSHIP_SWITCH}、consentId は {@code null}（紙同意書を伴わない）。</p>
     */
    private void handleGuardianshipSwitch(HttpServletRequest request,
                                          HttpServletResponse response,
                                          FilterChain chain,
                                          String proxyForHeader) throws ServletException, IOException {
        // 子ユーザーIDのパース
        Long childUserId;
        try {
            childUserId = Long.parseLong(proxyForHeader.trim());
        } catch (NumberFormatException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "X-Proxy-For-User-Id の形式が不正です。");
            return;
        }

        // リクエスト者（保護者）の認証確認
        Long guardianUserId = extractCurrentUserId();
        if (guardianUserId == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "後見切替を行うには認証が必要です。");
            return;
        }

        // 保護者リンク＋年齢ゲートの毎リクエスト再検証（副作用なし）。
        // ObjectProvider 遅延解決: 後見切替経路を踏んだここで初めて auth ドメイン Bean を解決する。
        SwitchVerdict verdict = guardianshipSwitchServiceProvider.getObject()
                .evaluateSwitch(guardianUserId, childUserId);
        switch (verdict) {
            case LINK_NOT_FOUND -> {
                log.warn("後見切替の再検証失敗（リンクなし）: guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                sendError(response, HttpServletResponse.SC_FORBIDDEN,
                        "有効な保護者リンクが見つからないため後見切替できません。");
                return;
            }
            case AGE_LOCKED -> {
                log.warn("後見切替の再検証失敗（年齢封印）: guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                sendError(response, HttpServletResponse.SC_FORBIDDEN,
                        "このお子さまは年齢到達のため後見切替できません。");
                return;
            }
            case ALLOWED -> {
                // 後見切替は会費支払い・所属管理・プロフィール編集・閲覧のため PAYMENT スコープのみ付与
                // （03_security §3.2「切替中に行えること」・最小権限）。consentId は紙同意書がないため null。
                // originalStorageLocation は NOT NULL 列のため固定値を渡す（null だと F14.1 代理入力で
                // proxy_input_records 保存時に NOT NULL 制約違反 500 になる）。
                proxyInputContext.activate(childUserId, null,
                        ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH.name(),
                        SWITCH_STORAGE_LOCATION_NA,
                        java.util.Set.of(FeatureScope.PAYMENT));
                log.debug("後見切替モード有効化: guardianUserId={}, childUserId={}", guardianUserId, childUserId);
                chain.doFilter(request, response);
            }
        }
    }

    private Long extractCurrentUserId() {
        return SecurityUtils.getCurrentUserIdOrNull();
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", message)));
    }
}
