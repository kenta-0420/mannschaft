package com.mannschaft.app.team.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * チーム側の加盟操作の認可ゲート（F01.2.1 §3.2「チームの加盟操作者」の解決）。
 *
 * <p>「チームの加盟操作者」とは、そのチームで {@code MANAGE_ORG_AFFILIATION} を持つ人
 * （チーム ADMIN は常に含む。DEPUTY_ADMIN・MEMBER は権限グループで付与されたときだけ）である。
 * 申請・取下げ・申請中一覧（2-B1）のほか、招待の承諾・拒否・離脱・制限の解除（2-C・2-D）が
 * 同じ判定を使うため、判定の入口を本クラスに1つにする。</p>
 *
 * <p>判定は {@code AccessControlService#checkPermission}（{@code RoleService.resolveEffectivePermissions} 経由）に
 * 一元化する。チーム ADMIN の既定付与は {@code role_permissions}（ADMIN・{@code is_default=1}）の行に依存し、
 * 暗黙の全権ではない（§3.2）。組織の ADMIN・SYSTEM_ADMIN であっても、チームで権限を持たなければ通らない。</p>
 *
 * <p>本クラスは<b>トランザクションの外</b>（Controller）から呼ぶ。認可判定は role ドメインの Repository へ到達するため、
 * team ドメインのトランザクション内に持ち込まない（ドメイン内にトランザクションを閉じる原則）。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamAffiliationAccessGuard {

    /** 権限名の定義（正本は Flyway の {@code permissions} への INSERT。権限名の enum は無い。§3.2）。 */
    public static final String MANAGE_ORG_AFFILIATION = "MANAGE_ORG_AFFILIATION";

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final AccessControlService accessControlService;

    /**
     * 操作者がそのチームの加盟操作者であることを要求する。違反時は 403。
     *
     * @param userId 操作者
     * @param teamId チーム ID
     */
    public void requireOperator(Long userId, Long teamId) {
        accessControlService.checkPermission(userId, teamId, SCOPE_TYPE_TEAM, MANAGE_ORG_AFFILIATION);
    }
}
