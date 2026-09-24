package com.mannschaft.app.role.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 柱①「ADMINゼロ根治」— 退会予定ユーザーが唯一のADMINであるスコープ。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §5.4 / §14。
 * {@code UserRoleRepository#findLastAdminScopes(Long)} が返す。Repository は
 * 「ADMIN数=1（自分のみ）」のスコープを機械的に列挙するのみで、承継候補の有無判定は
 * サービス層（{@code RoleSuccessionService}）の責務（§14 の分離方針）。</p>
 *
 * TODO 出陣で実装。
 */
@Getter
@Builder
public class LastAdminScope {

    /** ORGANIZATION / TEAM */
    private final String scopeType;

    /** teams.id または organizations.id */
    private final Long scopeId;

    /** 表示用スコープ名 */
    private final String scopeName;

    /** 自分以外のメンバー数（0人なら purge 時に自動 archive の対象） */
    private final long otherMembersCount;
}
