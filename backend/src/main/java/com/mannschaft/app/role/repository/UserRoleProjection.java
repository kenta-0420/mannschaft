package com.mannschaft.app.role.repository;

/**
 * {@link com.mannschaft.app.role.entity.UserRoleEntity} の軽量射影。
 *
 * <p>F00 ContentVisibilityResolver 基盤の {@code MembershipBatchQueryService}
 * からバルク所属判定用に利用される。Spring Data JPA の Interface Projection を
 * 用いて、必要なフィールドのみを 1 SQL で取得する。</p>
 *
 * <p>メソッド名は {@link com.mannschaft.app.role.entity.UserRoleEntity} の
 * getter と一致させること。</p>
 *
 * <p>役割名 ({@code roleName}) は本射影に含めない。Interface Projection で
 * 関連エンティティを引くと N+1 となる懸念があるため、{@code roleId} のみを返し、
 * 役割名解決は呼び出し元 ({@code MembershipBatchQueryService}) で別途行う方針
 * とする（A-3b の領分）。</p>
 *
 * <p>F00 設計書 §10.2 を参照。</p>
 */
public interface UserRoleProjection {

    /** ユーザーロール割当の ID。 */
    Long getId();

    /** 対象ユーザー ID。 */
    Long getUserId();

    /** スコープが TEAM の場合のチーム ID（ORGANIZATION スコープ時は null）。 */
    Long getTeamId();

    /** スコープが ORGANIZATION の場合の組織 ID（TEAM スコープ時は null）。 */
    Long getOrganizationId();

    /** ロール ID。役割名は呼び出し元で解決する。 */
    Long getRoleId();
}
