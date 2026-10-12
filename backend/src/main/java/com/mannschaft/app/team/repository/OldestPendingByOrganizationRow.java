package com.mannschaft.app.team.repository;

import java.time.LocalDateTime;

/**
 * {@link TeamOrgMembershipRepository#findOldestPendingCreatedAtByOrganization} の1行（F01.2.1 2-D・片付けの回復バッチの候補選び）。
 *
 * <p>JPQL の集計結果を受ける射影であり、生 SQL のバインド値には使わない。生 SQL を持つ Repository の中に置くと、
 * 時刻ゲッターの宣言が「生 SQL を発行するクラスがエンティティの時刻ゲッターを読む」番人
 * （{@code RawSqlTimeColumnGuardTest}）の検査対象と区別できないので、独立した型にする。</p>
 */
public interface OldestPendingByOrganizationRow {

    Long getOrganizationId();

    /** その組織の最も古い PENDING の {@code created_at}（JPA が読んだ壁時計。{@code TeamOrgClosureWatermark} で比べる）。 */
    LocalDateTime getOldestCreatedAt();
}
