package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03.11.1 募集キャンセル料の免除（waive）（設計書 §10）。
 *
 * <p>免除は必ず債権の放棄を行うが、申込ブロックの解除は「そのユーザーに他の未払いが残っていない場合」に限る。
 * ブロックの判定がユーザー単位（{@code existsByUserIdAndPaymentStatusIn}）である一方、免除は記録を 1 件ずつ
 * 指定して行うためである（§10.0）。免除の効果は免除した主催者の募集に閉じず、ブロックが実際に外れたときは
 * あらゆる募集に対して外れる。</p>
 *
 * <p>免除できるのは受取先側（TEAM の支払い管理権限者 / ORG の管理者 / 個人受取の本人）と
 * {@code SYSTEM_ADMIN} である。受取先の判定は escrow の payee に基づかせ、募集の作成者では判定しない
 * ——募集を作った者と謝礼の受取先は一致するとは限らず、作成者で判定すると免除できる範囲が受取先と食い違う。
 * キャンセル料を負っている本人は免除できない（債務者が自分の債務を消せてはならない・§10.2）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentCancellationFeeWaiveService {

    /** 免除理由の最大長（{@code notes VARCHAR(500)} に収まる長さ）。 */
    private static final int MAX_REASON_LENGTH = 500;

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;
    private final ConnectChargeService connectChargeService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * キャンセル料を免除する。
     *
     * @param recordId    対象のキャンセル記録 ID
     * @param actorUserId 操作者ユーザー ID
     * @param reason      免除理由（必須・最大 500 文字）
     */
    @Transactional
    public void waive(Long recordId, Long actorUserId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // 存在しない記録・論理削除済みの記録は 404（存在を推測させない）。
        RecruitmentCancellationRecordEntity record = cancellationRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_005));

        // 受取先の判定は payment ドメインへ委ね、recruitment から escrow を直接読まない（§3.4・§10.2）。
        boolean payeeSide = connectChargeService.isPayeeSettlementManager(
                EscrowSourceKind.RECRUITMENT, record.getListingId(), record.getParticipantId(), actorUserId);
        if (!payeeSide && !accessControlService.isSystemAdmin(actorUserId)) {
            // キャンセル料を負っている本人もここで弾かれる（受取先が偶然その本人である場合を除く）。
            log.warn("F03.11.1 免除の権限が無い呼び出しを拒否: recordId={}, actorUserId={}", recordId, actorUserId);
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 既に免除済みなら冪等に成功で返す（終端状態なら何でも 409、にはしない）。
        if (record.getPaymentStatus() == CancellationPaymentStatus.WAIVED) {
            log.info("F03.11.1 キャンセル料は既に免除済み（冪等・no-op）: recordId={}", recordId);
            return;
        }
        // 徴収済みのものは免除ではなく返金の話であり、混同させない。
        if (record.getPaymentStatus() == CancellationPaymentStatus.PAID) {
            throw new BusinessException(RecruitmentErrorCode.CANCELLATION_FEE_ALREADY_PAID);
        }

        CancellationPaymentStatus previousStatus = record.getPaymentStatus();
        record.waive(actorUserId, reason);
        cancellationRecordRepository.save(record);

        // 免除は金銭債権を消す操作である。誰がいつ何円を消したかを後から追えないまま実行させてはならない（§10.4）。
        auditLogService.record(
                AuditEventType.RECRUITMENT_CANCELLATION_FEE_WAIVED.name(),
                actorUserId,
                record.getUserId(),
                record.getTeamId(),
                null,
                null,
                null,
                null,
                String.format(
                        "{\"recordId\":%d,\"feeAmount\":%d,\"previousStatus\":\"%s\",\"operatorRole\":\"%s\"}",
                        recordId, record.getFeeAmount(), previousStatus,
                        payeeSide ? "PAYEE_SIDE" : "SYSTEM_ADMIN"));

        log.info("F03.11.1 キャンセル料を免除: recordId={}, actorUserId={}, feeAmount={}, 免除前={}",
                recordId, actorUserId, record.getFeeAmount(), previousStatus);
    }
}
