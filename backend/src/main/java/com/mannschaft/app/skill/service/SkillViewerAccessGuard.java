package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 他メンバーの資格を一覧で見る API（スキルマトリクス・資格検索・CSV エクスポート）の認可（CMP-261012-0313）。
 *
 * <p>F07.5 §2 の対象ロールどおり、許可するのは次の 3 者だけである。</p>
 * <ul>
 *   <li>SYSTEM_ADMIN（全組織・チームの資格データ参照）</li>
 *   <li>当該チームの ADMIN</li>
 *   <li>当該チームの DEPUTY_ADMIN のうち {@value #PERMISSION_MANAGE_MEMBER} 権限を持つ者
 *       （{@code role_permissions} の自動付与、または権限グループでの個別付与）</li>
 * </ul>
 * <p>それ以外（MANAGE_MEMBER を持たない DEPUTY_ADMIN・MEMBER・SUPPORTER・GUEST・非メンバー）は 403 COMMON_002。
 * 一覧型（{@code docs/security/01_authorization_baseline.md} §3.3.1）のため teamId の実在によらず同一応答とし、
 * 存在を隠す 404 には化けさせない。</p>
 *
 * <p>本クラスは {@code @Transactional} を付けない（認可を業務 tx の外で行う）。
 * SYSTEM_ADMIN を先に通すのは、{@link AccessControlService#checkAdminOrHasPermissionInScope} が
 * SYSTEM_ADMIN を特別扱いしない（同メソッドの Javadoc）ためである。</p>
 */
@Component
@RequiredArgsConstructor
public class SkillViewerAccessGuard {

    /** F07.5 §2 の「MANAGE_MEMBER 権限」。カタログ登録は {@code V239.20261012040500}。 */
    public static final String PERMISSION_MANAGE_MEMBER = "MANAGE_MEMBER";

    private static final String TEAM = "TEAM";

    private final AccessControlService accessControlService;

    /**
     * チームの全メンバーの資格を一覧で見てよいかを検証する。違反時は 403（COMMON_002）。
     *
     * @param userId 操作者
     * @param teamId path の teamId
     */
    public void checkCanViewTeamMemberSkills(Long userId, Long teamId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrHasPermissionInScope(userId, teamId, TEAM, PERMISSION_MANAGE_MEMBER);
    }
}
