package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * F03.11.1 募集キャンセル料の免除（waive）（設計書 §10）。
 *
 * <p>免除は必ず債権の放棄を行うが、申込ブロックの解除は「そのユーザーに他の未払いが残っていない場合」に限る。
 * ブロックの判定がユーザー単位（{@code existsByUserIdAndPaymentStatusIn}）である一方、免除は記録を 1 件ずつ
 * 指定して行うためである（§10.0）。</p>
 *
 * <p>免除できるのは受取先側（TEAM の支払い管理権限者 / ORG の管理者 / 個人受取の本人）と
 * {@code SYSTEM_ADMIN} である。受取先の判定は escrow の payee に基づかせ、募集の作成者では判定しない。
 * キャンセル料を負っている本人は免除できない（債務者が自分の債務を消せてはならない・§10.2）。</p>
 *
 * <p><b>第三陣（試練）時点の状態</b>: 宣言のみ。本体は第四陣（出陣）で実装する。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RecruitmentCancellationFeeWaiveService {

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
    public void waive(Long recordId, Long actorUserId, String reason) {
        throw new UnsupportedOperationException("F03.11.1 免除は第四陣で実装");
    }
}
