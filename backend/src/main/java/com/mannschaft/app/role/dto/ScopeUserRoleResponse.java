package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スコープ内ユーザー（ロール割当）レスポンスDTO。
 *
 * <p>認可根治 Wave5 で新設。従来 {@code AdminDashboardController#getUsers} は
 * {@code UserRoleRepository} を直叩きして {@code Page<UserRoleEntity>} を生返却しており、
 * Entity をレスポンスに晒さない規約に違反していた。</p>
 *
 * <p>本DTOと変換処理を <b>role ドメイン側</b>（{@code RoleService#getScopeUsers}）に置くのは、
 * ドメイン境界の原則「異なるドメインの Entity を直接参照しない／ドメイン間のデータ取得は
 * Service のメソッド呼び出し経由で行う」に従うため。admin ドメインが
 * {@code UserRoleEntity} を参照せずに済む。</p>
 */
@Getter
@RequiredArgsConstructor
public class ScopeUserRoleResponse {

    /** user_roles の割当 ID。 */
    private final Long id;

    /** 対象ユーザー ID。 */
    private final Long userId;

    /** 割り当てられたロール ID。 */
    private final Long roleId;

    /** チームスコープの割当なら team_id（組織スコープなら null）。 */
    private final Long teamId;

    /** 組織スコープの割当なら organization_id（チームスコープなら null）。 */
    private final Long organizationId;

    /** ロールを付与した操作者のユーザー ID（自己登録等では null）。 */
    private final Long grantedBy;

    /** 割当日時。 */
    private final LocalDateTime createdAt;

    /** 割当更新日時。 */
    private final LocalDateTime updatedAt;
}
