package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.AssignPositionRequest;
import com.mannschaft.app.membership.dto.EndPositionRequest;
import com.mannschaft.app.membership.dto.MemberPositionDto;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipDto;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MemberPositionEntity;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.entity.PositionEntity;
import com.mannschaft.app.membership.event.MembershipEndedEvent;
import com.mannschaft.app.membership.repository.MemberPositionRepository;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.PositionRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RolePermissionCleanupService;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * F00.5 メンバーシップ基盤再設計の中核サービス。
 *
 * <p>memberships / member_positions テーブルへの入会・退会・再加入・役職割当・終了の
 * 単一エントリポイント。</p>
 *
 * <p>Phase 4 完了: 二重書き込みコードを物理削除済み。memberships のみへの書き込みに一本化。
 * user_roles は SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / GUEST の権限ロール専用に縮退。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §7 / §13</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class MembershipService {

    private final MembershipRepository membershipRepository;
    private final MemberPositionRepository memberPositionRepository;
    private final PositionRepository positionRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final UserRowLockService userRowLockService;
    private final RolePermissionCleanupService rolePermissionCleanupService;

    /**
     * 入会処理。
     *
     * <p>設計書 §7.1 / §13.7（冪等性保証）に従い:</p>
     * <ol>
     *   <li>既存 active membership があれば、同一 role_kind なら冪等的にそれを返す</li>
     *   <li>異なる role_kind なら 409 ACTIVE_EXISTS</li>
     *   <li>memberships に INSERT</li>
     *   <li>MembershipChangedEvent(ASSIGNED) を発火</li>
     * </ol>
     */
    @Transactional
    public MembershipDto join(MembershipCreateRequest req) {
        lockUser(req.getUserId());
        validateScope(req.getScopeType(), req.getScopeId());

        // 冪等性チェック（§13.7）
        Optional<MembershipEntity> existing = membershipRepository.findActiveByUserAndScope(
                req.getUserId(), req.getScopeType(), req.getScopeId());
        if (existing.isPresent()) {
            MembershipEntity active = existing.get();
            if (active.getRoleKind() == effectiveRoleKind(req.getRoleKind())) {
                log.debug("入会冪等処理: 既存 membershipId={}", active.getId());
                return MembershipDto.from(active, false);
            }
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_ACTIVE_EXISTS);
        }

        // 再加入かどうかを履歴照会で算出
        boolean isRejoin = !membershipRepository.findHistoryByUserAndScope(
                req.getUserId(), req.getScopeType(), req.getScopeId()).isEmpty();

        // memberships に INSERT
        MembershipEntity entity = MembershipEntity.builder()
                .userId(req.getUserId())
                .scopeType(req.getScopeType())
                .scopeId(req.getScopeId())
                .roleKind(effectiveRoleKind(req.getRoleKind()))
                .invitedBy(req.getInvitedBy())
                .build();
        MembershipEntity saved = membershipRepository.save(entity);

        // ダッシュボードキャッシュ無効化用イベント発火
        eventPublisher.publishEvent(new MembershipChangedEvent(
                req.getUserId(), req.getScopeType().name(), req.getScopeId(),
                MembershipChangedEvent.ChangeType.ASSIGNED));

        // 監査ログ用イベント発行（SUPPORTER のフォロー参加は対象外 — MEMBER/ADMIN 等のみ）
        if (saved.getRoleKind() != RoleKind.SUPPORTER) {
            if (req.getScopeType() == ScopeType.TEAM) {
                Long invitedBy = req.getInvitedBy();
                eventPublisher.publishEvent(new TeamMemberAuditEvent(
                        invitedBy != null ? invitedBy : req.getUserId(),
                        req.getUserId(),
                        req.getScopeId(),
                        TeamMemberAuditEvent.SubType.JOINED));
            } else if (req.getScopeType() == ScopeType.ORGANIZATION) {
                Long invitedBy = req.getInvitedBy();
                eventPublisher.publishEvent(new OrganizationMemberAuditEvent(
                        invitedBy != null ? invitedBy : req.getUserId(),
                        req.getUserId(),
                        req.getScopeId(),
                        OrganizationMemberAuditEvent.SubType.JOINED));
            }
        }

        log.info("入会完了: membershipId={}, userId={}, scopeType={}, scopeId={}, roleKind={}, isRejoin={}, source={}",
                saved.getId(), req.getUserId(), req.getScopeType(), req.getScopeId(),
                saved.getRoleKind(), isRejoin, req.getSource());

        return MembershipDto.from(saved, isRejoin);
    }

    /**
     * 退会処理。
     *
     * <p>設計書 §7.2 に従い:</p>
     * <ol>
     *   <li>memberships の存在確認、既に退会済なら 409 ALREADY_LEFT</li>
     *   <li>last admin 保護: user_roles 側で当該 user × scope に ADMIN 行があり、
     *       かつ他 ADMIN がいない場合は 409 LAST_ADMIN_BLOCKED</li>
     *   <li>memberships UPDATE SET left_at=NOW(), leave_reason=...</li>
     *   <li>紐付く現役 member_positions を自動 ended_at セット</li>
     *   <li>MembershipChangedEvent(REMOVED) を発火</li>
     * </ol>
     */
    @Transactional
    public MembershipDto leave(Long membershipId, MembershipLeaveRequest req) {
        MembershipEntity entity = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_NOT_FOUND));

        // user行lock後に同じmembershipを再読込し、leave判定・更新を同一snapshotで行う。
        lockUser(entity.getUserId());
        entity = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_NOT_FOUND));

        if (!entity.isActive()) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_ALREADY_LEFT);
        }

        // 最後の ADMIN 保護（user_roles 側で判定）— RoleService の checkLastAdmin 相当を委譲
        lockAdminRows(entity.getScopeType(), entity.getScopeId());
        checkLastAdminProtectedByUserRoles(entity);

        // memberships を退会状態に更新
        LocalDateTime now = LocalDateTime.now();
        entity.setLeftAt(now);
        entity.setLeaveReason(req.getLeaveReason());
        membershipRepository.save(entity);
        rolePermissionCleanupService.removeMismatched(
                entity.getUserId(), entity.getScopeId(), entity.getScopeType().name(), null);

        // 紐付く現役役職を自動離任
        List<MemberPositionEntity> activePositions =
                memberPositionRepository.findCurrentByMembership(entity.getId());
        for (MemberPositionEntity mp : activePositions) {
            mp.setEndedAt(now);
            memberPositionRepository.save(mp);
        }

        // ダッシュボードキャッシュ無効化用イベント発火
        if (entity.getUserId() != null) {
            eventPublisher.publishEvent(new MembershipChangedEvent(
                    entity.getUserId(), entity.getScopeType().name(), entity.getScopeId(),
                    MembershipChangedEvent.ChangeType.REMOVED));

            // F15.3 §6.5: マイスコープフォルダ等の dangling 防止用イベント
            eventPublisher.publishEvent(new MembershipEndedEvent(
                    entity.getUserId(), entity.getScopeType(), entity.getScopeId()));
        }

        // 監査ログ用イベント発行（SUPPORTER の退会は対象外 — MEMBER/ADMIN 等のみ）
        if (entity.getUserId() != null && entity.getRoleKind() != RoleKind.SUPPORTER) {
            Long actorId = req.getRemovedBy() != null ? req.getRemovedBy() : entity.getUserId();
            if (entity.getScopeType() == ScopeType.TEAM) {
                eventPublisher.publishEvent(new TeamMemberAuditEvent(
                        actorId,
                        entity.getUserId(),
                        entity.getScopeId(),
                        TeamMemberAuditEvent.SubType.REMOVED));
            } else if (entity.getScopeType() == ScopeType.ORGANIZATION) {
                eventPublisher.publishEvent(new OrganizationMemberAuditEvent(
                        actorId,
                        entity.getUserId(),
                        entity.getScopeId(),
                        OrganizationMemberAuditEvent.SubType.REMOVED));
            }
        }

        log.info("退会完了: membershipId={}, userId={}, scopeType={}, scopeId={}, leaveReason={}, removedBy={}",
                entity.getId(), entity.getUserId(), entity.getScopeType(), entity.getScopeId(),
                entity.getLeaveReason(), req.getRemovedBy());

        return MembershipDto.from(entity, false);
    }

    /**
     * ユーザー × スコープ指定での退会処理（{@link #leave(Long, MembershipLeaveRequest)} の窓口版）。
     *
     * <p>membershipId ではなく「誰が・どのスコープを」離脱するかしか判らない呼び出し元
     * （role ドメインの除名・退会など）のために、アクティブ membership の解決を membership ドメイン内に
     * 閉じ込める。呼び出し元が {@link MembershipRepository} を直接注入する必要をなくす
     * （D-3 ArchUnit 準拠: {@code @Transactional} クラスは別ドメイン Repository に直接依存しない）。</p>
     *
     * <p>退会本体のロジックは {@link #leave(Long, MembershipLeaveRequest)} に委譲する。left_at /
     * leave_reason の確定・現役役職の自動離任・{@code MembershipChangedEvent(REMOVED)} /
     * {@code MembershipEndedEvent} / 監査イベントの発火はすべて委譲先が担う。</p>
     *
     * @param userId      対象ユーザー ID
     * @param scopeType   スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId     スコープ ID
     * @param leaveReason 退会理由
     * @param removedBy   除名を実行した操作者 ID（自主退会・システム処理では null）
     * @return アクティブ membership を退会させた場合 true、対象が無く何もしなかった場合 false
     */
    @Transactional
    public boolean leaveByUserAndScope(Long userId, ScopeType scopeType, Long scopeId,
                                       LeaveReason leaveReason, Long removedBy) {
        lockUser(userId);
        Optional<MembershipEntity> active =
                membershipRepository.findActiveByUserAndScope(userId, scopeType, scopeId);
        if (active.isEmpty()) {
            return false;
        }
        MembershipLeaveRequest req = new MembershipLeaveRequest();
        req.setLeaveReason(leaveReason);
        req.setRemovedBy(removedBy);
        leave(active.get().getId(), req);
        return true;
    }

    private void lockUser(Long userId) {
        userRowLockService.lock(userId);
    }

    /**
     * 役職割当。
     *
     * <p>設計書 §7.4.2 に従い、スコープ越境を必ず検証する。</p>
     */
    @Transactional
    public MemberPositionDto assignPosition(Long membershipId, AssignPositionRequest req) {
        MembershipEntity m = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_NOT_FOUND));

        if (!m.isActive()) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_ALREADY_LEFT);
        }

        PositionEntity p = positionRepository.findById(req.getPositionId())
                .orElseThrow(() -> new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_POSITION_CATALOG_NOT_FOUND));

        // スコープ越境検証
        if (p.getScopeType() != m.getScopeType() || !p.getScopeId().equals(m.getScopeId())) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_POSITION_SCOPE_MISMATCH);
        }

        if (!p.isAlive()) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_POSITION_CATALOG_NOT_FOUND);
        }

        LocalDateTime startedAt = req.getStartedAt() != null ? req.getStartedAt() : LocalDateTime.now();

        MemberPositionEntity entity = MemberPositionEntity.builder()
                .membershipId(membershipId)
                .positionId(req.getPositionId())
                .startedAt(startedAt)
                .assignedBy(req.getAssignedBy())
                .build();

        MemberPositionEntity saved;
        try {
            saved = memberPositionRepository.save(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // uq_member_positions_active 衝突
            throw new BusinessException(
                    MembershipBasisErrorCode.MEMBERSHIP_POSITION_ACTIVE_EXISTS, ex);
        }

        log.info("役職割当完了: memberPositionId={}, membershipId={}, positionId={}, assignedBy={}",
                saved.getId(), membershipId, req.getPositionId(), req.getAssignedBy());

        return MemberPositionDto.from(saved);
    }

    /**
     * 役職終了。
     *
     * <p>設計書 §7.4.3 に従い ended_at をセットする。期間逆転は CHECK 制約で
     * DB が拒否するが、アプリ層でも事前検証する。</p>
     */
    @Transactional
    public MemberPositionDto endPosition(Long memberPositionId, EndPositionRequest req) {
        MemberPositionEntity entity = memberPositionRepository.findById(memberPositionId)
                .orElseThrow(() -> new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_POSITION_NOT_FOUND));

        if (!entity.isActive()) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_POSITION_NOT_FOUND);
        }

        LocalDateTime endedAt = req.getEndedAt() != null ? req.getEndedAt() : LocalDateTime.now();
        if (endedAt.isBefore(entity.getStartedAt())) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PERIOD_INVERTED);
        }

        entity.setEndedAt(endedAt);
        memberPositionRepository.save(entity);

        log.info("役職終了完了: memberPositionId={}, endedAt={}", memberPositionId, endedAt);

        return MemberPositionDto.from(entity);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /** 既定 MEMBER。 */
    private RoleKind effectiveRoleKind(RoleKind requested) {
        return requested != null ? requested : RoleKind.MEMBER;
    }

    /** scope_type と scope_id の整合性を簡易検証する。 */
    private void validateScope(ScopeType scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null || scopeId <= 0) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_INVALID_SCOPE);
        }
    }

    /**
     * 最後の ADMIN 保護を user_roles 側で判定する。memberships の MEMBER/SUPPORTER 退会には影響しない（FR-11）。
     */
    private void checkLastAdminProtectedByUserRoles(MembershipEntity entity) {
        if (entity.getUserId() == null) {
            return;
        }
        Optional<RoleEntity> adminRoleOpt = roleRepository.findByName("ADMIN");
        if (adminRoleOpt.isEmpty()) {
            return;
        }
        Long adminRoleId = adminRoleOpt.get().getId();

        boolean isAdmin;
        long adminCount;
        if (entity.getScopeType() == ScopeType.TEAM) {
            isAdmin = userRoleRepository.existsByUserIdAndTeamIdAndRoleId(
                    entity.getUserId(), entity.getScopeId(), adminRoleId);
            adminCount = userRoleRepository.countByTeamIdAndRoleId(entity.getScopeId(), adminRoleId);
        } else {
            isAdmin = userRoleRepository.existsByUserIdAndOrganizationIdAndRoleId(
                    entity.getUserId(), entity.getScopeId(), adminRoleId);
            adminCount = userRoleRepository.countByOrganizationIdAndRoleId(entity.getScopeId(), adminRoleId);
        }
        if (isAdmin && adminCount <= 1) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_LAST_ADMIN_BLOCKED);
        }
    }

    /** 同一scopeのADMIN行を先にID順でロックし、last-admin判定と退会更新を直列化する。 */
    private void lockAdminRows(ScopeType scopeType, Long scopeId) {
        roleRepository.findByName("ADMIN").ifPresent(admin -> {
            if (scopeType == ScopeType.TEAM) {
                userRoleRepository.lockAdminsByTeamId(scopeId, admin.getId());
            } else if (scopeType == ScopeType.ORGANIZATION) {
                userRoleRepository.lockAdminsByOrganizationId(scopeId, admin.getId());
            }
        });
    }

    /**
     * 指定ユーザーがアクティブ（退会していない）に所属するチームの ID 一覧を返す。
     *
     * <p>マイページ チームプロジェクト集約（{@code GET /api/v1/me/team-projects}）が
     * 所属チーム ID 集合を取得する際、{@code todo} ドメインの {@code ProjectService} が
     * {@code membership} ドメインの {@code MembershipRepository} を直接注入することを避けるために
     * 本メソッドを提供する（D-3 ArchUnit 準拠: @Transactional クラスは別ドメイン Repository に
     * 直接依存しない）。プリミティブ（{@code List<Long>}）のみを返し、Entity を漏らさない。</p>
     *
     * @param userId 対象ユーザー ID
     * @return アクティブに所属するチームの scopeId 一覧（退会済みは除外）
     */
    public List<Long> getActiveTeamIdsByUser(Long userId) {
        return membershipRepository
                .findActiveByUserAndScopeType(userId, ScopeType.TEAM)
                .stream()
                .map(MembershipEntity::getScopeId)
                .toList();
    }

    public List<Long> getActiveTeamIdsIncludingRoleAssignments(Long userId) {
        return java.util.stream.Stream.concat(
                        userRoleRepository.findTeamIdsByUserId(userId).stream(),
                        getActiveTeamIdsByUser(userId).stream())
                .distinct()
                .toList();
    }

    /**
     * 指定ユーザーがアクティブ（退会していない）に所属する組織の ID 一覧を返す。
     *
     * <p>マイページ 組織プロジェクト集約（{@code GET /api/v1/me/org-projects}）が
     * 所属組織 ID 集合を取得する際、{@code todo} ドメインの {@code ProjectService} が
     * {@code membership} ドメインの {@code MembershipRepository} を直接注入することを避けるために
     * 本メソッドを提供する（D-3 ArchUnit 準拠）。プリミティブ（{@code List<Long>}）のみを返し、
     * Entity を漏らさない。</p>
     *
     * <p>{@link #getActiveTeamIdsByUser(Long)} の {@code ScopeType.ORGANIZATION} 版。</p>
     *
     * @param userId 対象ユーザー ID
     * @return アクティブに所属する組織の scopeId 一覧（退会済みは除外）
     */
    public List<Long> getActiveOrgIdsByUser(Long userId) {
        return membershipRepository
                .findActiveByUserAndScopeType(userId, ScopeType.ORGANIZATION)
                .stream()
                .map(MembershipEntity::getScopeId)
                .toList();
    }

    public List<Long> getActiveOrgIdsIncludingRoleAssignments(Long userId) {
        return java.util.stream.Stream.concat(
                        userRoleRepository.findOrganizationIdsByUserId(userId).stream(),
                        getActiveOrgIdsByUser(userId).stream())
                .distinct()
                .toList();
    }

    /**
     * 指定ユーザーが指定スコープ（単一）のアクティブメンバーかどうかを返す。
     *
     * <p>{@code schedule} ドメインの {@code GoogleCalendarService}（{@code @Transactional} クラス）が
     * 同期トグルの IDOR 閉塞（非メンバー拒否）でメンバーシップを確認する際、
     * {@code membership} ドメインの {@link MembershipRepository} を直接注入することを避けるための
     * 公開窓口（D-3 ArchUnit 準拠: @Transactional クラスは別ドメイン Repository に直接依存しない）。
     * {@code boolean} のみを返し、Entity を漏らさない。</p>
     *
     * @param userId    対象ユーザー ID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID（team_id または organization_id）
     * @return アクティブメンバーなら true（退会済み・非メンバーは false）
     */
    public boolean isActiveMember(Long userId, ScopeType scopeType, Long scopeId) {
        return membershipRepository.existsActiveByUserAndScope(userId, scopeType, scopeId);
    }

    /** authz統合用にactive direct membershipのrole_kindだけを返す。 */
    public Optional<RoleKind> findActiveRoleKind(Long userId, ScopeType scopeType, Long scopeId) {
        return membershipRepository.findActiveByUserAndScope(userId, scopeType, scopeId)
                .map(MembershipEntity::getRoleKind);
    }

    /**
     * 認可根治 Wave6: 指定スコープ集合（TEAM / ORGANIZATION 混在）に在籍する利用者の ID 一覧を返す。
     *
     * <p>{@code search} ドメインの横断検索が「閲覧者と同一スコープに所属する利用者」だけを
     * 利用者検索の候補に絞る際に用いる公開窓口。{@code search} ドメインが {@code membership}
     * ドメインの Repository を直接注入することを避ける（D-3 ArchUnit 準拠）。
     * プリミティブ（{@code List<Long>}）のみを返し、Entity を漏らさない。</p>
     *
     * <p>呼び出し側は {@code teamIds} / {@code orgIds} が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param teamIds 対象チーム scopeId 集合（非空・空ならダミー値）
     * @param orgIds  対象組織 scopeId 集合（非空・空ならダミー値）
     * @return 在籍者の user_id 一覧（DISTINCT・退会済みは除外）
     */
    public List<Long> getActiveUserIdsInScopes(Collection<Long> teamIds, Collection<Long> orgIds) {
        return membershipRepository.findActiveDistinctUserIdsByScopes(teamIds, orgIds);
    }

    /**
     * 指定スコープ（単一）に在籍するアクティブメンバーの user_id 一覧を joined_at 昇順で返す。
     *
     * <p>{@code schedule} ドメインの {@code ScheduleCommentService} がメンション候補の母集団
     * （親スコープの直属メンバー）を取得する際、{@code membership} ドメインの
     * {@link MembershipRepository} を直接注入することを避けるための公開窓口
     * （D-5 ArchUnit 準拠: 別ドメインの Repository へ直接依存しない）。
     * プリミティブ（{@code List<Long>}）のみを返し、Entity を漏らさない
     * （{@link #getActiveUserIdsInScopes(Collection, Collection)} の単一スコープ版）。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @return 在籍者の user_id 一覧（joined_at 昇順・退会済みは除外）
     */
    public List<Long> getActiveMemberUserIds(ScopeType scopeType, Long scopeId) {
        return membershipRepository.findAllActiveByScope(scopeType, scopeId).stream()
                .map(MembershipEntity::getUserId)
                .toList();
    }

}

