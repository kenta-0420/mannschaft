package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チームごとの予約既定ポリシーサービス。
 *
 * <p>承認モードの解決ロジックを一本化する。解決ルール（マスター御裁可）:
 * 「枠(slot)の値があればそれ／無ければチーム設定(reservation_policies)／それも無ければ AUTO」。</p>
 *
 * <p>レコードが存在しないチームは既定値（approvalMode=AUTO 等）として扱う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationPolicyService {

    private final ReservationPolicyRepository policyRepository;

    /**
     * チームの予約ポリシーを取得する。レコードが存在しない場合は
     * 既定値（approvalMode=AUTO / cancelDeadlineHours=24 / remindBeforeHours="24,1"）の
     * 未永続エンティティを返す。
     *
     * <p>本メソッドは DB を書き込まない。永続化が必要な場合は
     * {@link #updatePolicy(Long, ApprovalMode, Integer, String)} を使うこと。</p>
     *
     * @param teamId チームID
     * @return 該当チームの予約ポリシー（存在しなければ既定値の未永続エンティティ）
     */
    public ReservationPolicyEntity getOrDefault(Long teamId) {
        return policyRepository.findByTeamId(teamId)
                .orElseGet(() -> ReservationPolicyEntity.builder()
                        .teamId(teamId)
                        .build());
    }

    /**
     * 予約の承認モードを解決する。
     *
     * <p>解決ルール: 「枠の値があればそれ／無ければチーム設定／それも無ければ AUTO」。
     * すなわち {@code slot.approvalMode != null ? slot.approvalMode : getOrDefault(teamId).approvalMode}。
     * チーム設定が存在しなければ {@link #getOrDefault(Long)} が AUTO 既定を返すため、最終フォールバックは AUTO。</p>
     *
     * @param teamId チームID
     * @param slot   対象スロット（{@code approvalMode} が null の場合はチーム設定へフォールバック）
     * @return 解決された承認モード（必ず非 null）
     */
    public ApprovalMode resolveApprovalMode(Long teamId, ReservationSlotEntity slot) {
        if (slot != null && slot.getApprovalMode() != null) {
            return slot.getApprovalMode();
        }
        return getOrDefault(teamId).getApprovalMode();
    }

    /**
     * チームの予約ポリシーを更新する（upsert）。
     * レコードが存在しなければ新規作成し、存在すれば値を更新する。
     * null を渡したフィールドは更新しない（部分更新）。
     *
     * @param teamId              チームID
     * @param approvalMode        承認モード（null の場合は据え置き / 新規時は既定 AUTO）
     * @param cancelDeadlineHours キャンセル締切時間（null の場合は据え置き / 新規時は既定 24）
     * @param remindBeforeHours   リマインド CSV（null の場合は据え置き / 新規時は既定 "24,1"）
     * @return 更新後の予約ポリシーエンティティ
     */
    @Transactional
    public ReservationPolicyEntity updatePolicy(
            Long teamId, ApprovalMode approvalMode, Integer cancelDeadlineHours, String remindBeforeHours) {
        ReservationPolicyEntity entity = policyRepository.findByTeamId(teamId)
                .map(existing -> {
                    existing.updatePolicy(approvalMode, cancelDeadlineHours, remindBeforeHours);
                    return existing;
                })
                .orElseGet(() -> {
                    ReservationPolicyEntity.ReservationPolicyEntityBuilder builder =
                            ReservationPolicyEntity.builder().teamId(teamId);
                    if (approvalMode != null) {
                        builder.approvalMode(approvalMode);
                    }
                    if (cancelDeadlineHours != null) {
                        builder.cancelDeadlineHours(cancelDeadlineHours);
                    }
                    if (remindBeforeHours != null) {
                        builder.remindBeforeHours(remindBeforeHours);
                    }
                    return builder.build();
                });
        ReservationPolicyEntity saved = policyRepository.save(entity);
        log.info("予約ポリシー更新: teamId={}, approvalMode={}, cancelDeadlineHours={}, remindBeforeHours={}",
                teamId, saved.getApprovalMode(), saved.getCancelDeadlineHours(), saved.getRemindBeforeHours());
        return saved;
    }
}
