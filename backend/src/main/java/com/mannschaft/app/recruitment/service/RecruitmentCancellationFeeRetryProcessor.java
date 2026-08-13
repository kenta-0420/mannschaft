package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.SettleCancellationFeeOutcome;
import com.mannschaft.app.payment.escrow.SettleCancellationFeeResult;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.11.1 キャンセル料徴収リトライの 1 件分処理（設計書 §5.5・§12-9）。
 *
 * <p>既存 {@link RecruitmentPaymentRetryBatch} の {@code processRetry} は同一クラス内から自己呼び出しされており、
 * Spring プロキシを経由しないため {@code @Transactional} が実際には効いていなかった。1 件の失敗が他件を
 * 巻き込まないようにするには、1 件分の処理を別 Bean へ切り出して {@code REQUIRES_NEW} を効かせる必要がある。</p>
 *
 * <p>初回徴収とリトライで別ロジックを持たない。どちらも同じ入口
 * {@link ConnectChargeService#settleCancellationFee} を、同じ引き当ての三つ組・同じ冪等キーの素
 * （キャンセル記録 ID）で呼ぶ。冪等キーに {@code retryCount} を混ぜてはならない——失敗のたびにキーが変わると
 * Stripe の冪等が無効化され、二重課金しうる（§7.4）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentCancellationFeeRetryProcessor {

    /** リトライ上限（既存バッチと同値・§5.4）。 */
    public static final int MAX_RETRY_COUNT = 3;

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;
    private final ConnectChargeService connectChargeService;

    /**
     * 自己の Spring プロキシ。
     *
     * <p>{@link #processChunk} から {@link #processOne} を素の {@code this} 経由で呼ぶと AOP プロキシを通らず、
     * {@code REQUIRES_NEW} が効かない——これは本クラスが解消しようとしている当の欠陥そのものである。
     * よってチャンク内の 1 件ずつはプロキシ経由で呼ぶ。{@code @Lazy} は自己参照による循環を断つためであり、
     * Spring 管理外（単体テスト等）では {@code null} のまま {@code this} にフォールバックする。</p>
     */
    @Lazy
    @Autowired(required = false)
    private RecruitmentCancellationFeeRetryProcessor self;

    /**
     * チャンク 1 ページ分を、1 件ずつ独立したトランザクションで処理する。
     *
     * <p>本メソッド自体はトランザクションを張らない。張ると 1 件の失敗が
     * ロールバックオンリーでチャンク全体を巻き込む（AC-8 が守る性質が壊れる）。</p>
     *
     * @param chunk 処理対象のキャンセル記録
     * @return 徴収に成功した件数
     */
    public int processChunk(List<RecruitmentCancellationRecordEntity> chunk) {
        int success = 0;
        for (RecruitmentCancellationRecordEntity record : chunk) {
            if (proxy().processOne(record)) {
                success++;
            }
        }
        return success;
    }

    /**
     * 1 件分の徴収リトライを独立したトランザクションで処理する。
     *
     * <p>徴収できなかった場合はリトライ回数を進め、上限に達したら終端状態 {@code UNCOLLECTIBLE} へ移す。
     * {@code FAILED} のまま留め置くと「上限に達したがクエリの網からは外れ、拾われることも解決されることも
     * ない」宙吊りが残る（§5.4）。</p>
     *
     * @param record 対象のキャンセル記録
     * @return 徴収に成功したら true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(RecruitmentCancellationRecordEntity record) {
        SettleCancellationFeeResult result;
        try {
            result = connectChargeService.settleCancellationFee(
                    EscrowSourceKind.RECRUITMENT, record.getListingId(), record.getParticipantId(),
                    record.getFeeAmount(), String.valueOf(record.getId()));
        } catch (RuntimeException e) {
            log.warn("F03.11.1 キャンセル料のリトライ徴収が失敗: recordId={}", record.getId(), e);
            applyFailure(record);
            return false;
        }

        try {
            if (result.outcome() == SettleCancellationFeeOutcome.NOT_COLLECTIBLE) {
                applyFailure(record);
                return false;
            }
            // 徴収成立（部分キャプチャ／差額返金／既に徴収済みの no-op）。
            record.markPaid(result.stripeReference());
            cancellationRecordRepository.save(record);
            log.info("F03.11.1 キャンセル料のリトライ徴収に成功: recordId={}, outcome={}",
                    record.getId(), result.outcome());
            return true;
        } catch (RuntimeException e) {
            // Stripe は成功したが DB 更新で落ちたケース。トランザクションはロールバックし、記録は元の状態に戻る。
            // 次回のリトライは同一の冪等キー（記録 ID 由来）で再送されるため、Stripe は再課金せず
            // 最初の結果を返す。整合はそこで回復する（§7.4）。ここでキーを変えてはならない。
            log.error("F03.11.1 キャンセル料の徴収結果を記録へ反映できなかった（リトライで回復する）: recordId={}",
                    record.getId(), e);
            return false;
        }
    }

    /**
     * 徴収できなかったことを記録に反映する。上限到達なら終端状態へ落とす。
     */
    private void applyFailure(RecruitmentCancellationRecordEntity record) {
        try {
            record.incrementRetryCount();
            if (record.getPaymentRetryCount() >= MAX_RETRY_COUNT) {
                record.markUncollectible();
                log.warn("F03.11.1 キャンセル料のリトライ上限に到達し回収不能へ: recordId={}, userId={}, feeAmount={}",
                        record.getId(), record.getUserId(), record.getFeeAmount());
            } else if (record.getPaymentStatus() != CancellationPaymentStatus.FAILED) {
                record.markFailed();
            }
            cancellationRecordRepository.save(record);
        } catch (RuntimeException e) {
            log.error("F03.11.1 キャンセル料の徴収失敗を記録へ反映できなかった: recordId={}", record.getId(), e);
        }
    }

    /** REQUIRES_NEW を効かせるための呼び出し口（Spring 管理下ではプロキシ、それ以外は自身）。 */
    private RecruitmentCancellationFeeRetryProcessor proxy() {
        return self != null ? self : this;
    }
}
