package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipService;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p><b>ロック取得順序（Codex検分第2巡 P1 反映）</b>: 全メソッドで
 * {@code users}（{@link UserRowLockService}）→ {@code ADMIN} ロール定義行・スコープ内 ADMIN 行
 * （{@link AdminRoleMutationLockService#lockScopeAdminRowsAfterUsersLocked}、契約どおり
 * users ロック済み前提で呼ぶ）→ {@code user_roles} 行（{@code findByUserIdAndTeamIdForUpdate} 等）
 * → {@code memberships} 行（{@code MembershipService#isActiveMemberForUpdate}）の順で統一する。
 * これは {@code RoleService#transferOwnership}（{@code RoleService.java:641→650}）と同一順序であり、
 * 既存の通常経路（除名・脱退・降格・委譲）と対称にすることで、purge/バッチ経路（旧実装は
 * ADMIN 行を先にロックしてから候補ユーザー行をロックしていた）との間の相互待ちデッドロックを
 * 構造的に防ぐ。候補は「先に確定してからロック」できないため（誰が候補かは ADMIN 構成次第）、
 * ①ロック無し読みで候補を仮決定 → ②候補ユーザー行をロック（users 先） → ③ADMIN 行ロック →
 * ④ロック下で資格・ADMIN 構成を再検証 → 仮決定が覆っていれば候補集合を取り直してリトライ
 * （上限 {@link #MAX_SUCCESSION_ATTEMPTS} 回、尽きたら archive にフォールバック）という
 * 手順を踏む。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RoleSuccessionService {

    private static final String SCOPE_TEAM = "TEAM";
    private static final String NOTIF_ADMIN_SUCCESSION_FORCED = "ADMIN_SUCCESSION_FORCED";
    private static final String NOTIF_SOURCE_USER = "USER";
    /** 候補選定→ロック下再検証のリトライ上限（尽きたら archive にフォールバック）。 */
    private static final int MAX_SUCCESSION_ATTEMPTS = 5;

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final AdminRoleMutationLockService adminRoleMutationLockService;
    private final AuditLogService auditLogService;
    private final NotificationHelper notificationHelper;
    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final UserRowLockService userRowLockService;
    private final MembershipService membershipService;

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
        return selectSuccessionCandidateExcluding(scopeId, scopeType, Set.of());
    }

    private Optional<Long> selectSuccessionCandidateExcluding(Long scopeId, String scopeType, Set<Long> excludeUserIds) {
        boolean team = SCOPE_TEAM.equals(scopeType);
        List<Long> deputies = team
                ? userRoleRepository.findDeputyAdminCandidateIdsByTeam(scopeId)
                : userRoleRepository.findDeputyAdminCandidateIdsByOrganization(scopeId);
        Optional<Long> deputy = deputies.stream().filter(id -> !excludeUserIds.contains(id)).findFirst();
        if (deputy.isPresent()) {
            return deputy;
        }
        List<Long> members = team
                ? userRoleRepository.findMemberCandidateIdsByTeam(scopeId)
                : userRoleRepository.findMemberCandidateIdsByOrganization(scopeId);
        return members.stream().filter(id -> !excludeUserIds.contains(id)).findFirst();
    }

    /**
     * 候補ユーザーが当該スコープの active membership を持つかを、悲観ロック下で検証する
     * （{@code memberships} 行ロック。呼び出し前提: users → ADMIN 行のロックを取得済みであること）。
     *
     * <p>ユーザー自体の現役性（{@code deleted_at IS NULL AND status = 'ACTIVE'}）は
     * {@link UserRowLockService#lockAll} の戻り値（呼び出し元で users を先にロックした結果）で
     * 判定する。ここでは membership の在籍だけを見る。</p>
     */
    private boolean isMembershipEligible(Long userId, Long scopeId, String scopeType) {
        ScopeType membershipScopeType = SCOPE_TEAM.equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION;
        return membershipService.isActiveMemberForUpdate(userId, membershipScopeType, scopeId);
    }

    // 柱①「ADMINゼロ根治」検分反映（P1-2 / Codex第2巡 P1・ロック順序） — §12.6 のとおり、
    // 候補資格は「選定時点」と「ロック取得後の実行直前」の二段で検証する。
    //
    // 候補選定クエリ（findDeputyAdminCandidateIdsByTeam 等）は
    // users.deleted_at IS NULL AND users.status = 'ACTIVE' で「退会受付済み・
    // 匿名化済み・利用停止中」を除外している。この codebase の UserEntity は
    // 退会受付（requestDeletion()）と物理削除前提の論理削除の両方を単一の
    // deleted_at で表現し、匿名化（anonymize()）も必ず softDelete()（= deleted_at セット）
    // と対で呼ばれる設計（別カラムの withdrawal_requested_at/anonymized_at は実スキーマに
    // 存在しない）。よって SQL 側の条件は候補選定時点では十分であるが、選定から昇格実行までの
    // 間に候補自身が退会・停止・当該スコープからの離脱に陥るレースには対処できないため、
    // ロック取得後・書き込み直前に各呼び出し元（forceTransferForPurge /
    // promoteForBatchSuccession / forceAssignInitialAdminOnUnarchive）で再検証する
    // （ユーザー現役性は UserRowLockService のロック結果、membership 在籍は
    // isMembershipEligible から判定する。ロック順序は users → ADMIN 行 → membership）。

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
        Set<Long> excluded = new LinkedHashSet<>();
        excluded.add(withdrawingUserId);
        Long candidateId = null;

        for (int attempt = 1; attempt <= MAX_SUCCESSION_ATTEMPTS; attempt++) {
            // ① ロック無し読みで候補を仮決定。
            Optional<Long> tentative = selectSuccessionCandidateExcluding(scopeId, scopeType, excluded);

            // ② users を先にロック（退会者本人 + 仮候補）。ロック順序は users → ADMIN 行の順を
            // 守るため、ADMIN 行ロックより必ず前に行う。候補が無くても退会者本人はロックする
            // （後続の ADMIN 行ロックとの順序契約を崩さないため）。
            UserRowLockService.UserState candidateState = null;
            if (tentative.isPresent()) {
                Long tentativeId = tentative.get();
                candidateState = userRowLockService.lockAll(withdrawingUserId, tentativeId).get(tentativeId);
            } else {
                userRowLockService.lockAll(withdrawingUserId);
            }

            // ③ ADMIN 行ロック（users ロック済み前提、契約どおり）。
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

            if (tentative.isEmpty()) {
                // AC8: 候補ゼロ → archive
                archiveScope(scopeId, scopeType);
                return;
            }

            // ④ ロック下でユーザー現役性 + membership 資格を再検証。
            Long tentativeId = tentative.get();
            boolean eligible = candidateState == UserRowLockService.UserState.ACTIVE
                    && isMembershipEligible(tentativeId, scopeId, scopeType);
            if (eligible) {
                candidateId = tentativeId;
                break;
            }
            log.info("forceTransferForPurge: 候補資格の再検証で失格（選定後に状態変化）"
                            + " scopeType={}, scopeId={}, candidateId={}, attempt={}",
                    scopeType, scopeId, tentativeId, attempt);
            excluded.add(tentativeId);
        }

        if (candidateId == null) {
            // 候補が尽きた、またはリトライ上限到達 → archive にフォールバック。
            archiveScope(scopeId, scopeType);
            return;
        }

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
        Set<Long> excluded = new LinkedHashSet<>();
        Long candidateId = null;

        for (int attempt = 1; attempt <= MAX_SUCCESSION_ATTEMPTS; attempt++) {
            // ① ロック無し読みで候補を仮決定 → ② users を先にロック（ロック順序は forceTransferForPurge と同一）。
            Optional<Long> tentative = selectSuccessionCandidateExcluding(scopeId, scopeType, excluded);
            UserRowLockService.UserState candidateState = tentative.map(userRowLockService::lock).orElse(null);

            // ③ ADMIN 行ロック（users ロック済み前提）。
            List<Long> lockedAdminUserIds =
                    adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(scopeId, scopeType);
            if (!lockedAdminUserIds.isEmpty()) {
                // 既に是正済（並行実行・他経路での復旧）。
                return BatchSuccessionResult.NOT_NEEDED;
            }

            if (tentative.isEmpty()) {
                break;
            }

            // ④ ロック下でユーザー現役性 + membership 資格を再検証。
            Long tentativeId = tentative.get();
            boolean eligible = candidateState == UserRowLockService.UserState.ACTIVE
                    && isMembershipEligible(tentativeId, scopeId, scopeType);
            if (eligible) {
                candidateId = tentativeId;
                break;
            }
            log.info("promoteForBatchSuccession: 候補資格の再検証で失格 scopeType={}, scopeId={}, candidateId={}, attempt={}",
                    scopeType, scopeId, tentativeId, attempt);
            excluded.add(tentativeId);
        }

        if (candidateId == null) {
            archiveScope(scopeId, scopeType);
            return BatchSuccessionResult.ARCHIVED;
        }

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
     * させないよう、呼び出し元（コントローラ）は unarchive とセットで本メソッドを呼ぶこと。</p>
     *
     * <p><b>検分反映（P1-3 / Codex第2巡 P1・ロック順序）</b>: 指名ユーザーが「現役
     * （{@code deleted_at IS NULL} かつ {@code status = 'ACTIVE'}）かつ当該スコープの
     * active membership を持つ」ことを検証する。満たさない場合は
     * {@code BusinessException(RoleErrorCode.ROLE_001)}（404）を投げ、membership の無い
     * ADMIN 行を作らない。SYSTEM_ADMIN が明示指名したからといって、在籍実態の無いユーザーを
     * ADMIN 化することまでは許容しない（「ADMIN 指名を伴わない unarchive の拒否」は
     * あくまで「無指名を許さない」意であり、「在籍実態の無い者への指名まで許す」意ではない）。
     * ロック順序はクラス Javadoc のとおり users（{@code newAdminUserId}）→ ADMIN 行 →
     * membership の順で取得する。</p>
     *
     * @param scopeId       対象スコープ ID
     * @param scopeType     TEAM / ORGANIZATION
     * @param newAdminUserId SYSTEM_ADMIN が指名した初期 ADMIN のユーザー ID
     * @param actingSystemAdminId 実行した SYSTEM_ADMIN のユーザー ID（監査ログ用）
     * @throws BusinessException 指名ユーザーが現役でない、または当該スコープの active
     *                            membership を持たない場合（{@link RoleErrorCode#ROLE_001}・404）
     */
    @Transactional
    public void forceAssignInitialAdminOnUnarchive(
            Long scopeId, String scopeType, Long newAdminUserId, Long actingSystemAdminId) {
        // ① users を先にロック（新ADMIN指名者の現役性はこのロック結果で判定する）。
        UserRowLockService.UserState newAdminState = userRowLockService.lock(newAdminUserId);
        // ② ADMIN 行ロック（users ロック済み前提）。
        adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(scopeId, scopeType);

        boolean eligible = newAdminState == UserRowLockService.UserState.ACTIVE
                // ③ membership 再検証（ADMIN 行ロックの後）。
                && isMembershipEligible(newAdminUserId, scopeId, scopeType);
        if (!eligible) {
            log.warn("forceAssignInitialAdminOnUnarchive: 指名ユーザーが現役でないか非在籍のため拒否 "
                            + "scopeType={}, scopeId={}, newAdminUserId={}",
                    scopeType, scopeId, newAdminUserId);
            throw new BusinessException(RoleErrorCode.ROLE_001);
        }

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
