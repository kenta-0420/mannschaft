package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.TeamOrgAnalyticsErrorCode;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * アクセス解析集計取得（GET）の認可ガード（F10.8）。
 *
 * <p>{@code GET /api/v1/teams/{slug}/analytics} ・{@code GET /api/v1/organizations/{slug}/analytics} の
 * <b>Controller 入口</b>で、操作者が当該スコープのメンバー（または SYSTEM_ADMIN）であることを検証する。
 * 判定本体は既存基盤 {@link AccessControlService} に委譲し、独自の認可述語を発明しない
 * （既存戒め「認可は既存基盤に倣う」）。認可は共有メソッドに埋めず Controller 入口で閉じる
 * （既存戒め「共有メソッドにガードを付けるな」＝バッチ巻き添え回避）。手本は F03.8
 * {@code EventScopeAccessGuard} だが、応答コードの写像が異なる（下記）。</p>
 *
 * <h2>403 でなく 404 に統一する理由（設計書 §3.2 / §3.3・EventScopeAccessGuard との差異）</h2>
 * <p>{@code EventScopeAccessGuard} は非メンバーを 403（{@code COMMON_002}）で返すが、本ガードは
 * <b>非メンバーも存在しないスコープも一律 404（{@code TEAMANALYTICS_001}）で秘匿</b>する。
 * 403 を返すと「実在する非メンバースコープ」と「存在しないスコープ」を攻撃者が区別できてしまい、
 * メンバーシップの有無を探索されるため、両者を 404 に統一して観測を潰す（IDOR 隠蔽）。
 * SYSTEM_ADMIN は全スコープ許可。未認証（{@code userId == null}）は本ガードに到達する前に
 * 認証フィルタ層で 401 になる想定だが、防御的に {@code null} も 404 に写像する。</p>
 *
 * <p>閲覧可能ロール: SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER
 * （{@link AccessControlService#isMember} が memberships 統合で判定。SUPPORTER も member 扱い）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PageViewAnalyticsAccessGuard {

    private final AccessControlService accessControlService;

    /**
     * 集計取得の認可を検証する。非メンバー / 未認証は 404（{@code TEAMANALYTICS_001}）で秘匿する。
     *
     * <p>Controller は slug を {@code TeamService.resolveTeamId} /
     * {@code OrganizationService.resolveOrgId} で解決したうえで、解決済み {@code scopeId} を渡す
     * （存在しない slug は resolve 側が {@code TEAM_001} 等の 404 を投げるため、本ガードは
     * 「実在するスコープに対する非メンバーアクセス」を 404 に写像する役割）。</p>
     *
     * @param userId    操作者ユーザー ID（未認証なら {@code null}）
     * @param scopeType スコープ種別（{@code TEAM} / {@code ORGANIZATION}）
     * @param scopeId   URL パス由来の解決済みスコープ ID
     * @throws BusinessException 非メンバー / 未認証（{@code TEAMANALYTICS_001} / 404）
     */
    public void requireScopeMember(Long userId, PageViewScopeType scopeType, Long scopeId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId == null
                || !accessControlService.isMember(userId, scopeId, scopeType.name())) {
            throw new BusinessException(TeamOrgAnalyticsErrorCode.TEAMANALYTICS_001);
        }
    }
}
