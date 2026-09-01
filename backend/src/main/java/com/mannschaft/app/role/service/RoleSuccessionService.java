package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.LastAdminScope;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 柱①「ADMINゼロ根治」— purge（アカウント物理削除）例外経路限定の自動承継（案 C′）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §11〜§14。
 * 通常のオーナー委譲（承諾型オファー・{@link OwnershipTransferOfferService} 相当）とは別系統。
 * purge 経路は退会者本人の30日タイムリミットに阻害されないよう、承諾を待たない
 * 「承諾スキップの強制委譲」を採る（§10.11 後半 / §11.1）。</p>
 *
 * <p><b>①-0 直列化</b>: ADMIN 行の変更は既存 {@link AdminRoleMutationLockService} が提供する
 * 「ADMIN ロール定義行 → スコープ内 ADMIN 行」の悲観ロック（{@code SELECT ... FOR UPDATE}）を
 * 経由させる。{@code RoleService#removeMember} / {@code #leaveScope} / {@code #transferOwnership}
 * が既にこの経路でロックしているため、本サービスも同じ経路を再利用し、ロック方式を二重化しない
 * （decision: 新規ロックサービスを起こさず既存 {@code AdminRoleMutationLockService} を拡張なしで再利用）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RoleSuccessionService {

    private static final String SCOPE_TEAM = "TEAM";
    private static final String NOTIF_ADMIN_SUCCESSION_FORCED = "ADMIN_SUCCESSION_FORCED";
    private static final String NOTIF_SOURCE_USER = "USER";

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final AdminRoleMutationLockService adminRoleMutationLockService;
    private final AuditLogService auditLogService;
    private final NotificationHelper notificationHelper;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * {@code userId} が唯一の ADMIN であるスコープを全て返す（他メンバー0人のスコープも含む）。
     */
    public List<LastAdminScope> findLastAdminScopes(Long userId) {
        return userRoleRepository.findLastAdminScopes(userId);
    }

    /**
     * {@code userId} が唯一の ADMIN であるスコープのうち、他メンバーが1人以上いるものだけを返す
     * （deletion-preview 表示用。§14）。
     */
    public List<LastAdminScope> findBlockingLastAdminScopes(Long userId) {
        return findLastAdminScopes(userId).stream()
                .filter(scope -> scope.getOtherMembersCount() >= 1)
                .toList();
    }

    /**
     * §14 の退会受付ガード。他メンバー1人以上の lastAdmin スコープが残っていれば
     * {@code BusinessException(GdprErrorCode.GDPR_011)} を投げる（AC1）。
     * 他メンバー0人のスコープはブロックしない（purge 時 archive に委ねる、AC3）。
     *
     * <p>§12.7 のとおり、ここはロックなしの事前チェック（UX 用の早期拒否）。
     * 実行時（purge）の再判定は {@link #forceTransferForPurge} がロック下で行う。</p>
     */
    public void checkNoLastAdminScopes(Long userId) {
        if (!findBlockingLastAdminScopes(userId).isEmpty()) {
            throw new BusinessException(GdprErrorCode.GDPR_011);
        }
    }

    /**
     * §11.2 の優先順位・候補資格に従って承継候補を選定する。
     *
     * <p>優先順位: ① 候補資格を満たす DEPUTY_ADMIN の最古参（{@code created_at} 昇順・
     * {@code id} 昇順でタイブレーク） → ② 候補資格を満たす MEMBER の最古参
     * （{@code joined_at} 昇順・{@code id} 昇順） → 候補ゼロなら空を返す。</p>
     *
     * @param scopeId   対象スコープ ID
     * @param scopeType TEAM / ORGANIZATION
     * @return 候補ユーザー ID。候補資格者が1人もいなければ {@link Optional#empty()}
     */
    public Optional<Long> selectSuccessionCandidate(Long scopeId, String scopeType) {
        return selectSuccessionCandidateExcluding(scopeId, scopeType, null);
    }

    private Optional<Long> selectSuccessionCandidateExcluding(Long scopeId, String scopeType, Long excludeUserId) {
        boolean team = SCOPE_TEAM.equals(scopeType);
        List<Long> deputies = team
                ? userRoleRepository.findDeputyAdminCandidateIdsByTeam(scopeId)
                : userRoleRepository.findDeputyAdminCandidateIdsByOrganization(scopeId);
        Optional<Long> deputy = deputies.stream().filter(id -> !id.equals(excludeUserId)).findFirst();
        if (deputy.isPresent()) {
            return deputy;
        }
        List<Long> members = team
                ? userRoleRepository.findMemberCandidateIdsByTeam(scopeId)
                : userRoleRepository.findMemberCandidateIdsByOrganization(scopeId);
        return members.stream().filter(id -> !id.equals(excludeUserId)).findFirst();
    }

    /**
     * 承諾スキップの強制委譲を実行する（purge 経路専用）。
     *
     * <p>通常の承諾型 {@code acceptOffer} とは別メソッドとして分離する（§10.11 後半）。
     * 監査ログに {@code forced=true} を記録し、昇格された利用者へ通知を発行する。
     * ①-0 直列化のスコープロック下で候補資格を再検証してから実行する（§12.6）。</p>
     *
     * <p><b>冪等性（AC12）</b>: {@code AccountPurgedEvent} の再配送（at-least-once）に備え、
     * 「{@code withdrawingUserId} が現時点でもこのスコープの ADMIN であるか」を
     * ロック取得後に再確認する。既に処理済（委譲済 or 他経路で ADMIN 行が変化済）なら
     * 何もせず return する。{@code AccountPurgedEvent} は {@code purgeId} 相当の識別子を
     * payload に持たないため（{@code userId} のみ）、冪等キーは DB 上の実状態
     * （scope + userId の ADMIN 行の有無）で代替する（設計書 §12.6 からの実装上の妥当な逸脱。
     * 詳細は PR 報告に記載）。</p>
     *
     * <p>AC7: {@code scopeType} ごとに独立実行し、TEAM の承継処理が同一ユーザーの
     * ORGANIZATION 側 {@code user_roles} 行を一切変更しない（本メソッドは引数の
     * {@code scopeId}/{@code scopeType} のみを対象にクエリ・更新するため、構造的に越境しない）。</p>
     *
     * @param scopeId           対象スコープ ID
     * @param scopeType         TEAM / ORGANIZATION
     * @param withdrawingUserId 退会（purge）対象の旧 ADMIN ユーザー ID
     * @param purgeId           冪等キーの補助情報（現状は監査ログの metadata に残すのみ。上記参照）
     */
    @Transactional
    public void forceTransferForPurge(Long scopeId, String scopeType, Long withdrawingUserId, UUID purgeId) {
        // ①-0 直列化: 既存 AdminRoleMutationLockService でスコープの ADMIN 行を悲観ロックする。
        List<Long> lockedAdminUserIds =
                adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(scopeId, scopeType);

        // AC12 冪等性: withdrawingUserId が既にこのスコープの ADMIN でなければ、再配送または
        // 別経路で既に処理済（委譲済・除名済）。何もしない。
        if (!lockedAdminUserIds.contains(withdrawingUserId)) {
            log.info("forceTransferForPurge: 冪等スキップ（既に処理済）scopeType={}, scopeId={}, withdrawingUserId={}",
                    scopeType, scopeId, withdrawingUserId);
            return;
        }
        // AC3: withdrawingUserId 以外にも ADMIN が残っているなら是正不要。
        if (lockedAdminUserIds.size() > 1) {
            log.info("forceTransferForPurge: 他にADMINが残存のため是正不要 scopeType={}, scopeId={}",
                    scopeType, scopeId);
            return;
        }

        Optional<Long> candidateOpt = selectSuccessionCandidateExcluding(scopeId, scopeType, withdrawingUserId);
        if (candidateOpt.isEmpty()) {
            // AC8: 候補ゼロ → archive
            archiveScope(scopeId, scopeType);
            return;
        }

        Long candidateId = candidateOpt.get();
        promoteToAdmin(scopeId, scopeType, candidateId);

        boolean team = SCOPE_TEAM.equals(scopeType);
        auditLogService.record(
                (team ? AuditEventType.TEAM_ADMIN_SUCCESSION_FORCED : AuditEventType.ORGANIZATION_ADMIN_SUCCESSION_FORCED).name(),
                withdrawingUserId,
                candidateId,
                team ? scopeId : null,
                team ? null : scopeId,
                null, null, null,
                "{\"forced\":true,\"reason\":\"PURGE_LAST_ADMIN_SUCCESSION\""
                        + ",\"purgeId\":" + (purgeId == null ? "null" : "\"" + purgeId + "\"") + "}");

        notificationHelper.notify(
                candidateId,
                NOTIF_ADMIN_SUCCESSION_FORCED,
                NotificationPriority.HIGH,
                "管理者に自動指名されました",
                "先任の管理者の退会に伴い、あなたが管理者に自動で指名されました。",
                NOTIF_SOURCE_USER,
                null,
                team ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION,
                scopeId,
                null,
                withdrawingUserId);

        log.info("forceTransferForPurge 完了: scopeType={}, scopeId={}, withdrawingUserId={}, candidateId={}",
                scopeType, scopeId, withdrawingUserId, candidateId);
    }

    /** {@link #promoteForBatchSuccession(Long, String)} の結果種別。 */
    public enum BatchSuccessionResult { PROMOTED, ARCHIVED, NOT_NEEDED }

    /**
     * 柱①「ADMINゼロ根治」§13 — {@code AdminlessScopeSuccessionBatchService} 専用の是正実行。
     *
     * <p>{@code AccountPurgedEvent} の issuer（退会者コンテキスト）が存在しないバッチ経路のため、
     * {@link #forceTransferForPurge} とは別メソッドに分離する（既存裁定の踏襲）。
     * ロック方式・候補選定・archive フォールバックは {@link #forceTransferForPurge} と同一。</p>
     */
    @Transactional
    public BatchSuccessionResult promoteForBatchSuccession(Long scopeId, String scopeType) {
        List<Long> lockedAdminUserIds =
                adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(scopeId, scopeType);
        if (!lockedAdminUserIds.isEmpty()) {
            // 既に是正済（並行実行・他経路での復旧）。
            return BatchSuccessionResult.NOT_NEEDED;
        }

        Optional<Long> candidateOpt = selectSuccessionCandidate(scopeId, scopeType);
        if (candidateOpt.isEmpty()) {
            archiveScope(scopeId, scopeType);
            return BatchSuccessionResult.ARCHIVED;
        }

        Long candidateId = candidateOpt.get();
        promoteToAdmin(scopeId, scopeType, candidateId);

        boolean team = SCOPE_TEAM.equals(scopeType);
        auditLogService.record(
                (team ? AuditEventType.TEAM_ADMIN_SUCCESSION_FORCED : AuditEventType.ORGANIZATION_ADMIN_SUCCESSION_FORCED).name(),
                null,
                candidateId,
                team ? scopeId : null,
                team ? null : scopeId,
                null, null, null,
                "{\"forced\":true,\"reason\":\"ADMINLESS_SCOPE_BATCH_SUCCESSION\"}");

        notificationHelper.notify(
                candidateId,
                NOTIF_ADMIN_SUCCESSION_FORCED,
                NotificationPriority.HIGH,
                "管理者に自動指名されました",
                "管理者不在の状態が検出されたため、あなたが管理者に自動で指名されました。",
                NOTIF_SOURCE_USER,
                null,
                team ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION,
                scopeId,
                null,
                null);

        log.info("promoteForBatchSuccession 完了: scopeType={}, scopeId={}, candidateId={}",
                scopeType, scopeId, candidateId);
        return BatchSuccessionResult.PROMOTED;
    }

    /**
     * 柱①「ADMINゼロ根治」§15 — SYSTEM_ADMIN の force-unarchive 経由で初期 ADMIN を指名する。
     *
     * <p>{@code SystemAdminScopeForceUnarchiveController} 専用。ADMIN 不在のまま unarchive
     * させないよう、呼び出し元（コントローラ）は unarchive とセットで本メソッドを呼ぶこと。
     * 資格チェックは行わない（SYSTEM_ADMIN の明示指名を尊重する。§15 は「ADMIN 指名を伴わない
     * unarchive を拒否する」ことが本旨であり、指名対象の候補資格までは要求しない）。</p>
     *
     * @param scopeId       対象スコープ ID
     * @param scopeType     TEAM / ORGANIZATION
     * @param newAdminUserId SYSTEM_ADMIN が指名した初期 ADMIN のユーザー ID
     * @param actingSystemAdminId 実行した SYSTEM_ADMIN のユーザー ID（監査ログ用）
     */
    @Transactional
    public void forceAssignInitialAdminOnUnarchive(
            Long scopeId, String scopeType, Long newAdminUserId, Long actingSystemAdminId) {
        adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(scopeId, scopeType);
        promoteToAdmin(scopeId, scopeType, newAdminUserId);

        boolean team = SCOPE_TEAM.equals(scopeType);
        auditLogService.record(
                (team ? AuditEventType.TEAM_ADMIN_SUCCESSION_FORCED : AuditEventType.ORGANIZATION_ADMIN_SUCCESSION_FORCED).name(),
                actingSystemAdminId,
                newAdminUserId,
                team ? scopeId : null,
                team ? null : scopeId,
                null, null, null,
                "{\"forced\":true,\"reason\":\"SYSTEM_ADMIN_FORCE_UNARCHIVE\"}");

        log.info("forceAssignInitialAdminOnUnarchive 完了: scopeType={}, scopeId={}, newAdminUserId={}, actingSystemAdminId={}",
                scopeType, scopeId, newAdminUserId, actingSystemAdminId);
    }

    /** 候補ユーザーを既存ロール行から ADMIN 行へ差し替える（{@code RoleService#transferOwnership} と同型）。 */
    private void promoteToAdmin(Long scopeId, String scopeType, Long candidateId) {
        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        boolean team = SCOPE_TEAM.equals(scopeType);
        Optional<UserRoleEntity> existing = team
                ? userRoleRepository.findByUserIdAndTeamIdForUpdate(candidateId, scopeId)
                : userRoleRepository.findByUserIdAndOrganizationIdForUpdate(candidateId, scopeId);
        // delete→save が同一 scope_key を再挿入するため flush で DELETE を先に確定させる
        // （uq_user_roles_user_scope ユニーク制約の衝突回避。RoleService#transferOwnership と同型）。
        existing.ifPresent(row -> {
            userRoleRepository.delete(row);
            userRoleRepository.flush();
        });

        var builder = UserRoleEntity.builder()
                .userId(candidateId)
                .roleId(adminRole.getId());
        if (team) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
        userRoleRepository.save(builder.build());
    }

    /** AC8: 候補資格者が1人もいないスコープを archive する（既存 archive API を再利用）。 */
    private void archiveScope(Long scopeId, String scopeType) {
        try {
            if (SCOPE_TEAM.equals(scopeType)) {
                teamService.archiveTeam(scopeId);
            } else {
                organizationService.archiveOrganization(scopeId);
            }
            log.info("forceTransferForPurge: 候補ゼロのためスコープをアーカイブ scopeType={}, scopeId={}",
                    scopeType, scopeId);
        } catch (BusinessException e) {
            // 既に archive 済（多重配送・並行処理由来）は冪等に握りつぶさず記録だけ残す。
            log.info("forceTransferForPurge: スコープは既にアーカイブ済 scopeType={}, scopeId={}, code={}",
                    scopeType, scopeId, e.getErrorCode().getCode());
        }
    }
}
