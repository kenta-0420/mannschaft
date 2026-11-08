package com.mannschaft.app.common;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * メンバーシップ・ロール・権限を確認する認可の共通契約。
 * 呼び出し側へ業務実装やトランザクション管理の型を公開しない。
 */
public interface AccessControlService {

    /** 運営スコープの値。スコープ種別の列挙値へ変換せず認可入口で扱う。 */
    String PLATFORM_SCOPE_TYPE = "PLATFORM";

    /** TEAM所属の集合照会キー。 */
    record TeamUserKey(Long teamId, Long userId) { }

    /** SYSTEM_ADMINの全体昇格を使わない在籍資格。 */
    record TeamMembershipState(boolean active, boolean regularMemberOrAbove) { }

    /** 候補のTEAM在籍資格を2照会で返す。本人の公開状態は呼び出し側で確認する。 */
    Map<TeamUserKey, TeamMembershipState> getTeamMembershipStates(java.util.Collection<TeamUserKey> keys);

    /** 非メンバーを403で拒否する。 */
    void checkMembership(Long userId, Long scopeId, String scopeType);

    /** スコープに在籍するメンバーか判定する。 */
    boolean isMember(Long userId, Long scopeId, String scopeType);

    /** スコープに在籍中のメンバーIDを一括取得する。 */
    List<Long> listActiveMemberIds(Long scopeId, String scopeType);

    /** 在籍中の正式メンバーまたはスコープ管理者か判定する。 */
    boolean isRegularMemberOrAbove(Long userId, Long scopeId, String scopeType);

    /** 正式メンバー以上でなければ403で拒否する。 */
    void checkRegularMemberOrAbove(Long userId, Long scopeId, String scopeType);

    /** スコープに正式メンバーとして在籍中のIDを一括取得する。 */
    List<Long> listActiveRegularMemberIds(Long scopeId, String scopeType);

    /** 直接所属または組織配下の応答母集団への所属を判定する。 */
    boolean isMemberOrDescendant(Long userId, Long scopeId, String scopeType);

    /** SUPPORTERを含める設定に従って応答母集団への所属を判定する。 */
    boolean isMemberOrDescendant(Long userId, Long scopeId, String scopeType, boolean includeSupporters);

    /** 直接所属または組織配下の応答母集団への所属を要求する。 */
    void checkMembershipOrDescendant(Long userId, Long scopeId, String scopeType);

    /** SUPPORTERを含める設定に従って応答母集団への所属を要求する。 */
    void checkMembershipOrDescendant(Long userId, Long scopeId, String scopeType,
            boolean includeSupporters);

    /** スコープの有効ロール名を返す。該当しなければnull。 */
    String getRoleName(Long userId, Long scopeId, String scopeType);

    /** 権限ロールと所属ロールから最強の有効ロール名を返す。 */
    String resolveEffectiveRoleName(Long userId, Long scopeId, String scopeType);

    /** 候補者の有効ロール名を2照会で一括解決する。 */
    Map<Long, String> resolveEffectiveRoleNames(
            java.util.Collection<Long> userIds, Long scopeId, String scopeType);

    /** 組織への直接所属と配下チームの所属を合成してロール名を一括解決する。 */
    Map<Long, String> resolveEffectiveRoleNamesIncludingOrgDescendants(
            java.util.Collection<Long> userIds, Long organizationId);

    /** スコープのSUPPORTERか判定する。 */
    boolean isSupporter(Long userId, Long scopeId, String scopeType);

    /** 当該スコープのADMINまたはDEPUTY_ADMINか判定する。 */
    boolean isAdminOrAbove(Long userId, Long scopeId, String scopeType);

    /** 当該スコープのADMINか判定する。 */
    boolean isAdmin(Long userId, Long scopeId, String scopeType);

    /** 指定ロール以上の有効ロールを持つか判定する。 */
    boolean hasRoleOrAbove(Long userId, Long scopeId, String scopeType, String requiredRoleName);

    /** ADMINまたはDEPUTY_ADMINでなければ403で拒否する。 */
    void checkAdminOrAbove(Long userId, Long scopeId, String scopeType);

    /** スコープADMIN本人のみ許可し、SYSTEM_ADMINは拒否する。 */
    void checkScopeAdminOnly(Long userId, Long scopeId, String scopeType);

    /** スコープADMIN本人で、SYSTEM_ADMINではないか判定する。 */
    boolean canActAsScopeAdminOnly(Long userId, Long scopeId, String scopeType);

    /** 組織ADMIN、または指定権限を持つDEPUTY_ADMINであることを要求する。 */
    void checkAdminOrHasPermission(Long userId, Long scopeId, String scopeType, String permissionName);

    /** TEAMまたはORGANIZATIONのADMIN、あるいは指定権限を持つDEPUTY_ADMINか判定する。 */
    boolean hasAdminOrPermissionInScope(
            Long userId, Long scopeId, String scopeType, String permissionName);

    /** 当該スコープのADMIN、または指定権限を持つDEPUTY_ADMINであることを要求する。 */
    void checkAdminOrHasPermissionInScope(
            Long userId, Long scopeId, String scopeType, String permissionName);

    /** SYSTEM_ADMINか判定する。 */
    boolean isSystemAdmin(Long userId);

    /** 未削除かつACTIVEの利用者か判定する。複数スコープの最新ロール解決で一度だけ使う。 */
    boolean isActiveUser(Long userId);

    /** SYSTEM_ADMINでなければ403で拒否する。 */
    void checkSystemAdmin(Long userId);

    /** PLATFORMはSYSTEM_ADMIN、他スコープはスコープ管理者であることを要求する。 */
    void checkAdminOrAboveIncludingPlatform(Long userId, Long scopeId, String scopeType);

    /** 対象者が唯一のSYSTEM_ADMINであれば既存の退会拒否エラーを投げる。 */
    void checkNotLastSystemAdmin(Long userId);

    /** 当該スコープの指定権限を要求する。 */
    void checkPermission(Long userId, Long scopeId, String scopeType, String permissionName);

    /** 当該スコープの指定権限を持つか判定する。 */
    boolean hasPermission(Long userId, Long scopeId, String scopeType, String permissionName);

    /** 本人または当該スコープのADMIN以上であることを要求する。 */
    void checkOwnerOrAdmin(Long currentUserId, Long resourceOwnerId,
            Long scopeId, String scopeType);

    /** 見守り者から対象者へのACTIVEなケアリンクを要求する。 */
    void checkCareLink(Long watcherUserId, Long careRecipientUserId);

    /** 見守り者から対象者へのACTIVEなケアリンクの有無を返す。nullのIDはfalse。 */
    boolean hasActiveCareLink(Long watcherUserId, Long careRecipientUserId);

    /** 現在のACTIVE所属のスコープIDと参加日時を返す。 */
    Map<Long, LocalDateTime> findActiveMembershipJoinedAtByScope(Long userId, String scopeType);

    /** 指定スコープのACTIVEなdistinctメンバー数を返す。 */
    int countActiveDistinctMembers(String scopeType, Long scopeId);

    /** 複数スコープのACTIVEなdistinctメンバー数を一括取得する。 */
    Map<Long, Integer> countActiveDistinctMembersByScopes(
            String scopeType, java.util.Collection<Long> scopeIds);

    /** 権限ロールと在籍情報の和集合から所属スコープIDを列挙する。 */
    Set<Long> findAffiliatedScopeIds(Long userId, String scopeType);

    /** ADMINまたはDEPUTY_ADMINとして管理するスコープIDを列挙する。 */
    Set<Long> findAdminOrAboveScopeIds(Long userId, String scopeType);
}
