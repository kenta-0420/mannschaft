package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.DisputeResolution;
import com.mannschaft.app.recruitment.NoShowReason;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 Phase 5b: NO_SHOW マーク・異議申立サービス。
 *
 * 設計書 §5.8 (NO_SHOW フロー) を参照。
 * 通知は F04.9 実装後に統合予定（現在はログ出力のみ）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentNoShowService {

    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentNoShowRecordRepository noShowRepository;
    private final AccessControlService accessControlService;

    // ===========================================
    // 管理者による NO_SHOW マーク
    // ===========================================

    /**
     * 管理者が参加者を NO_SHOW としてマークする（仮マーク = confirmed=false）。
     * 24時間後に確定バッチが confirmed=true にする。
     */
    @Transactional
    public RecruitmentNoShowRecordEntity markNoShow(Long participantId, Long adminUserId) {
        RecruitmentParticipantEntity participant = participantRepository.findById(participantId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        // 権限チェック: 対象募集のスコープ管理者であること
        accessControlService.checkAdminOrAbove(
                adminUserId, participant.getListingId(), "RECRUITMENT");

        // CONFIRMED のみマーク可能
        if (participant.getStatus() != RecruitmentParticipantStatus.CONFIRMED) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        // 既に NO_SHOW 記録があれば重複防止
        noShowRepository.findByParticipantId(participantId).ifPresent(r -> {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_DISPUTED);
        });

        // 参加者ステータスを NO_SHOW に変更
        participant.markNoShow();
        participantRepository.save(participant);

        // NO_SHOW 記録を仮マーク（confirmed=false）で作成
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(participantId)
                .listingId(participant.getListingId())
                .userId(participant.getUserId())
                .reason(NoShowReason.ADMIN_MARKED)
                .recordedBy(adminUserId)
                .build();
        RecruitmentNoShowRecordEntity saved = noShowRepository.save(record);

        // TODO: F04.9 実装後に RECRUITMENT_NO_SHOW_RECORDED 通知を送信
        log.info("F03.11 Phase5b NO_SHOW仮マーク: participantId={}, userId={}, recordedBy={}",
                participantId, participant.getUserId(), adminUserId);

        return saved;
    }

    // ===========================================
    // 異議申立
    // ===========================================

    /**
     * ユーザーが自分の NO_SHOW に異議申立を行う。
     * ペナルティ設定の dispute_allowed_days 以内のみ可能。
     */
    @Transactional
    public RecruitmentNoShowRecordEntity dispute(Long recordId, Long userId) {
        RecruitmentNoShowRecordEntity record = noShowRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND));

        // 本人チェック
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(RecruitmentErrorCode.VISIBILITY_DENIED);
        }

        if (record.isDisputed()) {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_DISPUTED);
        }

        // 14日以内（固定値、設定から取れる場合は将来改善）
        if (record.getRecordedAt().plusDays(14).isBefore(LocalDateTime.now())) {
            throw new BusinessException(RecruitmentErrorCode.NO_SHOW_DISPUTE_DEADLINE_EXCEEDED);
        }

        record.dispute();
        noShowRepository.save(record);

        // TODO: F04.9 実装後に主催者へ RECRUITMENT_NO_SHOW_DISPUTE_RAISED 通知
        log.info("F03.11 Phase5b 異議申立: recordId={}, userId={}", recordId, userId);

        return record;
    }

    /**
     * 管理者が異議申立を解決する。
     * REVOKED の場合、ペナルティ再計算が必要（PenaltyService に委譲）。
     */
    @Transactional
    public RecruitmentNoShowRecordEntity resolveDispute(
            Long recordId, Long adminUserId, DisputeResolution resolution) {
        RecruitmentNoShowRecordEntity record = noShowRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND));

        if (!record.isDisputed()) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        record.resolveDispute(resolution);
        RecruitmentNoShowRecordEntity saved = noShowRepository.save(record);

        log.info("F03.11 Phase5b 異議申立解決: recordId={}, resolution={}", recordId, resolution);

        return saved;
    }

    // ===========================================
    // 照会
    // ===========================================

    /** ユーザー自身の NO_SHOW 履歴取得。 */
    public List<RecruitmentNoShowRecordEntity> getMyHistory(Long userId) {
        return noShowRepository.findByUserId(userId);
    }
}
