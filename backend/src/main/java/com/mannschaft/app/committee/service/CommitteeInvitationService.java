package com.mannschaft.app.committee.service;

import com.mannschaft.app.committee.dto.CommitteeInviteRequest;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationResolution;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 委員会招集サービス。招集状の送付・一覧・取り下げ・受諾・辞退を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommitteeInvitationService {

    private final CommitteeRepository committeeRepository;
    private final CommitteeMemberRepository committeeMemberRepository;
    private final CommitteeInvitationRepository committeeInvitationRepository;

    /** デフォルト有効期間（日数） */
    private static final int DEFAULT_EXPIRES_IN_DAYS = 7;

    // ========================================
    // 招集状送付
    // ========================================

    /**
     * 招集状を送付する結果を表す record。
     * スキップ件数をメタデータとして保持する。
     */
    public record SendInvitationsResult(
            List<CommitteeInvitationEntity> sentInvitations,
            int skippedExistingMemberCount,
            int skippedExistingInvitationCount
    ) {}

    /**
     * 招集状を送付する。
     * 認可: CHAIR または VICE_CHAIR。
     * 既に現役メンバーの場合はスキップ（エラーにせずメタ情報で報告）。
     * 既に未解決招集がある場合はスキップ（重複招集を防ぐ）。
     */
    @Transactional
    public SendInvitationsResult sendInvitations(Long committeeId, CommitteeInviteRequest request,
                                                  Long currentUserId) {
        // 委員会存在確認
        committeeRepository.findById(committeeId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 認可チェック: CHAIR または VICE_CHAIR
        if (!hasChairOrViceChairRole(committeeId, currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        CommitteeRole proposedRole = request.getProposedRole() != null
                ? request.getProposedRole()
                : CommitteeRole.MEMBER;
        int expiresInDays = request.getExpiresInDays() != null
                ? request.getExpiresInDays()
                : DEFAULT_EXPIRES_IN_DAYS;
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(expiresInDays);

        List<CommitteeInvitationEntity> sentInvitations = new ArrayList<>();
        int skippedExistingMemberCount = 0;
        int skippedExistingInvitationCount = 0;

        for (Long inviteeUserId : request.getInviteeUserIds()) {
            // 既に現役メンバーの場合はスキップ
            if (committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, inviteeUserId)) {
                skippedExistingMemberCount++;
                continue;
            }

            // 既に未解決招集がある場合はスキップ
            if (committeeInvitationRepository.existsByCommitteeIdAndInviteeUserIdAndResolvedAtIsNull(
                    committeeId, inviteeUserId)) {
                skippedExistingInvitationCount++;
                continue;
            }

            // 招集状を作成して保存
            CommitteeInvitationEntity invitation = CommitteeInvitationEntity.builder()
                    .committeeId(committeeId)
                    .inviteeUserId(inviteeUserId)
                    .proposedRole(proposedRole)
                    .inviteToken(UUID.randomUUID().toString())
                    .invitedBy(currentUserId)
                    .message(request.getMessage())
                    .expiresAt(expiresAt)
                    .build();

            sentInvitations.add(committeeInvitationRepository.save(invitation));
        }

        return new SendInvitationsResult(sentInvitations, skippedExistingMemberCount, skippedExistingInvitationCount);
    }

    // ========================================
    // 招集中一覧
    // ========================================

    /**
     * 招集中の招集状一覧を取得する。
     * 認可: CHAIR または VICE_CHAIR。
     */
    public List<CommitteeInvitationEntity> listPendingInvitations(Long committeeId, Long currentUserId) {
        // 委員会存在確認
        committeeRepository.findById(committeeId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 認可チェック: CHAIR または VICE_CHAIR
        if (!hasChairOrViceChairRole(committeeId, currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        return committeeInvitationRepository.findByCommitteeIdAndResolvedAtIsNull(committeeId);
    }

    // ========================================
    // 招集取り下げ
    // ========================================

    /**
     * 招集状を取り下げる。
     * 認可: 招集者（invitedBy）または CHAIR。
     * 未解決の招集のみキャンセル可。
     */
    @Transactional
    public void cancelInvitation(Long invitationId, Long currentUserId) {
        CommitteeInvitationEntity invitation = committeeInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.INVITATION_NOT_FOUND));

        // 未解決チェック
        if (invitation.getResolvedAt() != null) {
            throw new BusinessException(CommitteeErrorCode.INVITATION_ALREADY_RESOLVED);
        }

        // 認可チェック: 招集者本人 または CHAIR
        boolean isInviter = currentUserId.equals(invitation.getInvitedBy());
        boolean isChair = isChairRole(invitation.getCommitteeId(), currentUserId);
        if (!isInviter && !isChair) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        invitation.markCancelled();
        committeeInvitationRepository.save(invitation);
    }

    // ========================================
    // 招集受諾・辞退
    // ========================================

    /**
     * 招集状をトークンで受諾する。
     * 冪等: 既に ACCEPTED 済みなら 200 で既存メンバーシップを返す。
     * EXPIRED/CANCELLED なら 410 Gone に相当するエラーをスロー。
     */
    @Transactional
    public CommitteeMemberEntity acceptInvitation(String inviteToken, Long currentUserId) {
        CommitteeInvitationEntity invitation = committeeInvitationRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.INVITATION_TOKEN_INVALID));

        // 既に ACCEPTED 済みの場合は冪等に既存メンバーシップを返す
        if (CommitteeInvitationResolution.ACCEPTED.equals(invitation.getResolution())) {
            return committeeMemberRepository
                    .findByCommitteeIdAndUserIdAndLeftAtIsNull(invitation.getCommitteeId(), currentUserId)
                    .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_MEMBER));
        }

        // 被招集者とcurrentUserIdが一致するか確認
        if (!invitation.getInviteeUserId().equals(currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // EXPIRED または CANCELLED の場合はエラー
        if (invitation.getResolution() == CommitteeInvitationResolution.EXPIRED
                || invitation.getResolution() == CommitteeInvitationResolution.CANCELLED) {
            throw new BusinessException(CommitteeErrorCode.INVITATION_EXPIRED);
        }

        // 未解決だが期限切れの場合もエラー
        if (invitation.getResolvedAt() == null && invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            invitation.markExpired();
            committeeInvitationRepository.save(invitation);
            throw new BusinessException(CommitteeErrorCode.INVITATION_EXPIRED);
        }

        // 既に現役メンバーの場合は ACCEPTED にして既存メンバーシップを返す
        if (committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(
                invitation.getCommitteeId(), currentUserId)) {
            invitation.markAccepted();
            committeeInvitationRepository.save(invitation);
            return committeeMemberRepository
                    .findByCommitteeIdAndUserIdAndLeftAtIsNull(invitation.getCommitteeId(), currentUserId)
                    .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_MEMBER));
        }

        // 招集状を受諾済みにしてメンバーを追加
        invitation.markAccepted();
        committeeInvitationRepository.save(invitation);

        CommitteeMemberEntity newMember = CommitteeMemberEntity.builder()
                .committeeId(invitation.getCommitteeId())
                .userId(currentUserId)
                .role(invitation.getProposedRole())
                .joinedAt(LocalDateTime.now())
                .invitedBy(invitation.getInvitedBy())
                .build();

        return committeeMemberRepository.save(newMember);
    }

    /**
     * 招集状をトークンで辞退する。
     * 冪等: 既に DECLINED でも 200 を返す。
     * EXPIRED/CANCELLED なら 410 Gone に相当するエラーをスロー。
     */
    @Transactional
    public void declineInvitation(String inviteToken, Long currentUserId) {
        CommitteeInvitationEntity invitation = committeeInvitationRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.INVITATION_TOKEN_INVALID));

        // 既に DECLINED 済みの場合は冪等に 200 を返す
        if (CommitteeInvitationResolution.DECLINED.equals(invitation.getResolution())) {
            return;
        }

        // 被招集者とcurrentUserIdが一致するか確認
        if (!invitation.getInviteeUserId().equals(currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // EXPIRED または CANCELLED の場合はエラー
        if (invitation.getResolution() == CommitteeInvitationResolution.EXPIRED
                || invitation.getResolution() == CommitteeInvitationResolution.CANCELLED) {
            throw new BusinessException(CommitteeErrorCode.INVITATION_EXPIRED);
        }

        // 未解決だが期限切れの場合もエラー
        if (invitation.getResolvedAt() == null && invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            invitation.markExpired();
            committeeInvitationRepository.save(invitation);
            throw new BusinessException(CommitteeErrorCode.INVITATION_EXPIRED);
        }

        // 招集状を辞退済みにする
        invitation.markDeclined();
        committeeInvitationRepository.save(invitation);
    }

    // ========================================
    // プライベートヘルパーメソッド
    // ========================================

    /**
     * ユーザーが委員会の CHAIR または VICE_CHAIR かどうかを返す。
     */
    private boolean hasChairOrViceChairRole(Long committeeId, Long userId) {
        return committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId)
                .map(m -> CommitteeRole.CHAIR.equals(m.getRole()) || CommitteeRole.VICE_CHAIR.equals(m.getRole()))
                .orElse(false);
    }

    /**
     * ユーザーが委員会の CHAIR かどうかを返す。
     */
    private boolean isChairRole(Long committeeId, Long userId) {
        return committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId)
                .map(m -> CommitteeRole.CHAIR.equals(m.getRole()))
                .orElse(false);
    }
}
