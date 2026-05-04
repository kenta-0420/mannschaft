package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.BusinessException;
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
import com.mannschaft.app.membership.repository.MemberPositionRepository;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.PositionRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

        if (!entity.isActive()) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_ALREADY_LEFT);
        }

        // 最後の ADMIN 保護（user_roles 側で判定）— RoleService の checkLastAdmin 相当を委譲
        checkLastAdminProtectedByUserRoles(entity);

        // memberships を退会状態に更新
        LocalDateTime now = LocalDateTime.now();
        entity.setLeftAt(now);
        entity.setLeaveReason(req.getLeaveReason());
        membershipRepository.save(entity);

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
        }

        log.info("退会完了: membershipId={}, userId={}, scopeType={}, scopeId={}, leaveReason={}, removedBy={}",
                entity.getId(), entity.getUserId(), entity.getScopeType(), entity.getScopeId(),
                entity.getLeaveReason(), req.getRemovedBy());

        return MembershipDto.from(entity, false);
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

}

