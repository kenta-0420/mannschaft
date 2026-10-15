package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.TimelineErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * タイムラインのスコープ ID 文字列（slug または Long 文字列）を内部 Long ID に解決する共有コンポーネント。
 *
 * <p>読み取り経路（{@code TimelineFeedController#getFeed}）と書き込み経路
 * （{@code TimelinePostController#createPost}）の双方から利用し、解決ロジックを一元化する。
 * 従来はフィードコントローラーに private メソッドとして実装されており、書き込み経路に
 * 同等の解決が存在しなかったため、FE が slug を送ると投稿作成が 400 で落ちていた。</p>
 *
 * <p>slug 解決は {@link TeamService#resolveTeamId} / {@link OrganizationService#resolveOrgId} の
 * Service 経由で行い、Repository を直注入しない（ドメイン境界原則）。</p>
 */
@Component
@RequiredArgsConstructor
public class TimelineScopeIdResolver {

    /** slug 解決用: ドメイン境界原則により Service を経由する（Repository 直注入禁止）。 */
    private final TeamService teamService;
    /** slug 解決用: ドメイン境界原則により Service を経由する（Repository 直注入禁止）。 */
    private final OrganizationService organizationService;

    /**
     * スコープ ID 文字列（slug または Long 文字列）を内部 Long ID に解決する。
     *
     * <ul>
     *   <li>TEAM スコープ: 数値文字列はそのまま parse、非数値は {@link TeamService#resolveTeamId} で slug 解決</li>
     *   <li>ORGANIZATION スコープ: 同上を {@link OrganizationService#resolveOrgId} で</li>
     *   <li>その他スコープ: 数値文字列は parse、変換不能なら {@code 0L} にフォールバック</li>
     * </ul>
     *
     * <p>TEAM/ORGANIZATION で slug が解決不能な場合は {@link TimelineErrorCode#POST_NOT_FOUND} を投げる。</p>
     *
     * @param scopeType  スコープ種別（例: "TEAM", "ORGANIZATION", "PUBLIC"）
     * @param scopeIdStr スコープ ID 文字列（slug または Long 文字列）
     * @return 内部 Long ID
     */
    public Long resolve(String scopeType, String scopeIdStr) {
        if (!"TEAM".equals(scopeType) && !"ORGANIZATION".equals(scopeType)) {
            try {
                return Long.parseLong(scopeIdStr);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        try {
            return Long.parseLong(scopeIdStr);
        } catch (NumberFormatException e) {
            // 数値でない場合は slug として Service 経由で解決する（Repository 直注入禁止）
            try {
                if ("TEAM".equals(scopeType)) {
                    return teamService.resolveTeamId(scopeIdStr);
                } else {
                    return organizationService.resolveOrgId(scopeIdStr);
                }
            } catch (BusinessException ex) {
                throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
            }
        }
    }
}
