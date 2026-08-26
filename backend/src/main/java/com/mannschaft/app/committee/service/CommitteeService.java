package com.mannschaft.app.committee.service;

import com.mannschaft.app.committee.dto.CommitteeCreateRequest;
import com.mannschaft.app.committee.dto.CommitteeStatusTransitionRequest;
import com.mannschaft.app.committee.dto.CommitteeUpdateRequest;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 委員会サービス。委員会の CRUD・ステータス遷移・メンバー管理を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommitteeService {

    private final CommitteeRepository committeeRepository;
    private final CommitteeMemberRepository committeeMemberRepository;
    private final AccessControlService accessControlService;

    /** 委員会メンバーシップ・委員会内ロールに基づく認可判定の一元窓口。 */
    private final CommitteeAccessGuard committeeAccessGuard;

    // ========================================
    // 委員会 CRUD
    // ========================================

    /**
     * 委員会を設立する。
     * 認可: ORG_ADMIN または MANAGE_COMMITTEE 権限保持者。
     */
    @Transactional
    public CommitteeEntity createCommittee(Long organizationId, CommitteeCreateRequest request, Long currentUserId) {
        // 認可チェック: ORG_ADMIN または MANAGE_COMMITTEE 権限
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, organizationId, "ORGANIZATION");
        if (!isAdmin) {
            try {
                accessControlService.checkPermission(currentUserId, organizationId, "ORGANIZATION", "MANAGE_COMMITTEE");
            } catch (BusinessException e) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }

        // 名前重複チェック
        if (committeeRepository.existsByOrganizationIdAndName(organizationId, request.getName())) {
            throw new BusinessException(CommitteeErrorCode.NAME_DUPLICATE);
        }

        // 委員会エンティティを構築して保存
        CommitteeEntity committee = CommitteeEntity.builder()
                .organizationId(organizationId)
                .name(request.getName())
                .description(request.getDescription())
                .purposeTag(request.getPurposeTag())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .status(CommitteeStatus.DRAFT)
                .visibilityToOrg(request.getVisibilityToOrg() != null
                        ? request.getVisibilityToOrg()
                        : CommitteeVisibility.NAME_ONLY)
                .defaultConfirmationMode(request.getDefaultConfirmationMode() != null
                        ? request.getDefaultConfirmationMode()
                        : ConfirmationMode.OPTIONAL)
                .defaultAnnouncementEnabled(request.getDefaultAnnouncementEnabled() != null
                        ? request.getDefaultAnnouncementEnabled()
                        : true)
                .defaultDistributionScope(request.getDefaultDistributionScope() != null
                        ? request.getDefaultDistributionScope()
                        : DistributionScope.COMMITTEE_ONLY)
                .createdBy(currentUserId)
                .build();
        committeeRepository.save(committee);

        // 初期委員長をメンバーとして追加
        CommitteeMemberEntity chair = CommitteeMemberEntity.builder()
                .committeeId(committee.getId())
                .userId(request.getInitialChairUserId())
                .role(CommitteeRole.CHAIR)
                .joinedAt(LocalDateTime.now())
                .invitedBy(currentUserId)
                .build();
        committeeMemberRepository.save(chair);

        return committee;
    }

    /**
     * 委員会一覧を取得する。
     * 認可: 組織メンバーのみ。HIDDEN委員会は委員会メンバーまたはORG_ADMINのみ表示。
     */
    public Page<CommitteeEntity> listCommittees(Long organizationId, CommitteeStatus statusFilter,
                                                 Long currentUserId, Pageable pageable) {
        // 組織メンバーシップ確認
        accessControlService.checkMembership(currentUserId, organizationId, "ORGANIZATION");

        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, organizationId, "ORGANIZATION");

        // ページング取得
        Page<CommitteeEntity> committees;
        if (statusFilter != null) {
            committees = committeeRepository.findByOrganizationIdAndStatus(organizationId, statusFilter, pageable);
        } else {
            committees = committeeRepository.findByOrganizationId(organizationId, pageable);
        }

        // ADMIN は全件表示
        if (isAdmin) {
            return committees;
        }

        // 非ADMIN: HIDDEN委員会は委員会メンバーのみ表示
        List<Long> myCommitteeIds = committeeMemberRepository.findActiveCommitteeIdsByUserId(currentUserId);
        return committees.map(c -> {
            if (CommitteeVisibility.HIDDEN.equals(c.getVisibilityToOrg()) && !myCommitteeIds.contains(c.getId())) {
                return null; // フィルタ用のマーカー（Controllerでnullチェックして除外する）
            }
            return c;
        });
    }

    /**
     * 委員会詳細を取得する。
     * HIDDEN委員会は非メンバーに404を返す（存在漏洩防止）。
     */
    public CommitteeEntity getCommittee(Long committeeId, Long currentUserId) {
        CommitteeEntity committee = getCommitteeOrThrow(committeeId);

        boolean isMember = isCommitteeMember(committeeId, currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, committee.getOrganizationId(), "ORGANIZATION");

        // HIDDENかつ非メンバーかつ非ADMINは404
        if (CommitteeVisibility.HIDDEN.equals(committee.getVisibilityToOrg()) && !isMember && !isAdmin) {
            throw new BusinessException(CommitteeErrorCode.NOT_FOUND);
        }

        // 非HIDDEN委員会: 組織メンバーなら閲覧可
        if (!isMember && !isAdmin) {
            accessControlService.checkMembership(currentUserId, committee.getOrganizationId(), "ORGANIZATION");
        }

        return committee;
    }

    /**
     * 委員会情報を更新する。
     * 認可: CHAIR または VICE_CHAIR。
     */
    @Transactional
    public CommitteeEntity updateCommittee(Long committeeId, CommitteeUpdateRequest request, Long currentUserId) {
        CommitteeEntity committee = getCommitteeOrThrow(committeeId);

        // 認可チェック: CHAIR or VICE_CHAIR
        committeeAccessGuard.requireCommitteeRole(
                committeeId, currentUserId, CommitteeRole.CHAIR, CommitteeRole.VICE_CHAIR);

        // 名前変更時は重複チェック
        if (request.getName() != null && !request.getName().equals(committee.getName())) {
            if (committeeRepository.existsByOrganizationIdAndName(committee.getOrganizationId(), request.getName())) {
                throw new BusinessException(CommitteeErrorCode.NAME_DUPLICATE);
            }
        }

        // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
        // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
        committee.applyUpdate(
                request.getName() != null ? request.getName() : committee.getName(),
                request.getDescription() != null ? request.getDescription() : committee.getDescription(),
                request.getPurposeTag() != null ? request.getPurposeTag() : committee.getPurposeTag(),
                request.getStartDate() != null ? request.getStartDate() : committee.getStartDate(),
                request.getEndDate() != null ? request.getEndDate() : committee.getEndDate(),
                request.getVisibilityToOrg() != null ? request.getVisibilityToOrg() : committee.getVisibilityToOrg(),
                request.getDefaultConfirmationMode() != null
                        ? request.getDefaultConfirmationMode()
                        : committee.getDefaultConfirmationMode(),
                request.getDefaultAnnouncementEnabled() != null
                        ? request.getDefaultAnnouncementEnabled()
                        : committee.isDefaultAnnouncementEnabled(),
                request.getDefaultDistributionScope() != null
                        ? request.getDefaultDistributionScope()
                        : committee.getDefaultDistributionScope()
        );

        return committeeRepository.save(committee);
    }

    /**
     * ステータス遷移を実行する。
     * 認可: CHAIR または ORG_ADMIN。
     * 遷移規則:
     *   ACTIVATE: DRAFT → ACTIVE
     *   CLOSE: ACTIVE → CLOSED
     *   ARCHIVE: CLOSED → ARCHIVED, ACTIVE → ARCHIVED
     *   REOPEN: CLOSED → ACTIVE
     */
    @Transactional
    public CommitteeEntity transitionStatus(Long committeeId, CommitteeStatusTransitionRequest request,
                                             Long currentUserId) {
        CommitteeEntity committee = getCommitteeOrThrow(committeeId);

        // 認可チェック: CHAIR または ORG_ADMIN
        boolean isChair = committeeAccessGuard.hasCommitteeRole(committeeId, currentUserId, CommitteeRole.CHAIR);
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, committee.getOrganizationId(), "ORGANIZATION");
        if (!isChair && !isAdmin) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        CommitteeStatus currentStatus = committee.getStatus();
        String action = request.getAction();
        CommitteeStatus newStatus;
        LocalDateTime newArchivedAt = committee.getArchivedAt();

        switch (action) {
            case "ACTIVATE":
                if (currentStatus != CommitteeStatus.DRAFT) {
                    throw new BusinessException(CommitteeErrorCode.INVALID_STATUS_TRANSITION);
                }
                newStatus = CommitteeStatus.ACTIVE;
                break;
            case "CLOSE":
                if (currentStatus != CommitteeStatus.ACTIVE) {
                    throw new BusinessException(CommitteeErrorCode.INVALID_STATUS_TRANSITION);
                }
                newStatus = CommitteeStatus.CLOSED;
                break;
            case "ARCHIVE":
                if (currentStatus != CommitteeStatus.CLOSED && currentStatus != CommitteeStatus.ACTIVE) {
                    throw new BusinessException(CommitteeErrorCode.INVALID_STATUS_TRANSITION);
                }
                newStatus = CommitteeStatus.ARCHIVED;
                newArchivedAt = LocalDateTime.now();
                break;
            case "REOPEN":
                if (currentStatus != CommitteeStatus.CLOSED) {
                    throw new BusinessException(CommitteeErrorCode.INVALID_STATUS_TRANSITION);
                }
                newStatus = CommitteeStatus.ACTIVE;
                break;
            default:
                throw new BusinessException(CommitteeErrorCode.INVALID_STATUS_TRANSITION);
        }

        // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
        committee.applyStatusTransition(newStatus, newArchivedAt);
        return committeeRepository.save(committee);
    }

    // ========================================
    // メンバー管理
    // ========================================

    /**
     * メンバーのロールを変更する。
     * 認可: CHAIR のみ。
     */
    @Transactional
    public CommitteeMemberEntity updateMemberRole(Long committeeId, Long targetUserId,
                                                   CommitteeRole newRole, Long currentUserId) {
        getCommitteeOrThrow(committeeId);

        // 認可チェック: CHAIR のみ
        committeeAccessGuard.requireCommitteeRole(committeeId, currentUserId, CommitteeRole.CHAIR);

        CommitteeMemberEntity member = getMemberOrThrow(committeeId, targetUserId);

        // CHAIR → 非CHAIR への変更時: CHAIR が 1 名以下になる場合はエラー
        if (CommitteeRole.CHAIR.equals(member.getRole()) && !CommitteeRole.CHAIR.equals(newRole)) {
            long chairCount = committeeMemberRepository.countByCommitteeIdAndRoleAndLeftAtIsNull(
                    committeeId, CommitteeRole.CHAIR);
            if (chairCount <= 1) {
                throw new BusinessException(CommitteeErrorCode.CHAIR_REQUIRED);
            }
        }

        member.updateRole(newRole);
        return committeeMemberRepository.save(member);
    }

    /**
     * メンバーを解任する。
     * 認可: CHAIR のみ。
     */
    @Transactional
    public void removeMember(Long committeeId, Long targetUserId, Long currentUserId) {
        getCommitteeOrThrow(committeeId);

        // 認可チェック: CHAIR のみ
        committeeAccessGuard.requireCommitteeRole(committeeId, currentUserId, CommitteeRole.CHAIR);

        CommitteeMemberEntity member = getMemberOrThrow(committeeId, targetUserId);

        // CHAIR を解任する場合: CHAIR が 1 名以下になる場合はエラー
        if (CommitteeRole.CHAIR.equals(member.getRole())) {
            long chairCount = committeeMemberRepository.countByCommitteeIdAndRoleAndLeftAtIsNull(
                    committeeId, CommitteeRole.CHAIR);
            if (chairCount <= 1) {
                throw new BusinessException(CommitteeErrorCode.CHAIR_REQUIRED);
            }
        }

        member.leave();
        committeeMemberRepository.save(member);
    }

    /**
     * 委員会から自発的に離脱する。
     */
    @Transactional
    public void leaveCommittee(Long committeeId, Long currentUserId) {
        getCommitteeOrThrow(committeeId);

        // 認可チェック: 当該委員会の現役メンバー本人のみが離脱できる
        CommitteeMemberEntity member = committeeAccessGuard.requireCommitteeMember(committeeId, currentUserId);

        // 唯一のCHAIRの場合は離脱不可
        if (CommitteeRole.CHAIR.equals(member.getRole())) {
            long chairCount = committeeMemberRepository.countByCommitteeIdAndRoleAndLeftAtIsNull(
                    committeeId, CommitteeRole.CHAIR);
            if (chairCount <= 1) {
                throw new BusinessException(CommitteeErrorCode.LAST_CHAIR_CANNOT_LEAVE);
            }
        }

        member.leave();
        committeeMemberRepository.save(member);
    }

    /**
     * メンバー一覧を取得する。
     * 認可: 委員会メンバーのみ。
     */
    public List<CommitteeMemberEntity> listMembers(Long committeeId, Long currentUserId) {
        getCommitteeOrThrow(committeeId);

        committeeAccessGuard.requireCommitteeMember(committeeId, currentUserId);

        return committeeMemberRepository.findByCommitteeIdAndLeftAtIsNull(committeeId);
    }

    // ========================================
    // プライベートヘルパーメソッド
    // ========================================

    /**
     * 委員会が存在するか確認し、存在しなければ NOT_FOUND をスローする。
     */
    private CommitteeEntity getCommitteeOrThrow(Long committeeId) {
        return committeeRepository.findById(committeeId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));
    }

    /**
     * ユーザーの現役メンバーシップを取得し、存在しなければ NOT_MEMBER をスローする。
     */
    private CommitteeMemberEntity getMemberOrThrow(Long committeeId, Long userId) {
        return committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_MEMBER));
    }

    /**
     * 委員会の現役メンバー数を取得する。
     */
    public int getMemberCount(Long committeeId) {
        return committeeMemberRepository.findByCommitteeIdAndLeftAtIsNull(committeeId).size();
    }

    /**
     * ユーザーが委員会の現役メンバーかどうかを返す。
     */
    private boolean isCommitteeMember(Long committeeId, Long userId) {
        return committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId);
    }

    /**
     * ユーザーの委員会ロールを取得する。非メンバーの場合は null を返す。
     */
    public CommitteeRole getCommitteeRole(Long committeeId, Long userId) {
        return committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId)
                .map(CommitteeMemberEntity::getRole)
                .orElse(null);
    }
}
