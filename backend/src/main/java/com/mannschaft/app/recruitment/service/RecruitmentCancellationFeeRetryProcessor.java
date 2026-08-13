package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * F03.11.1 キャンセル料徴収リトライの 1 件分処理（設計書 §5.5・§12-9）。
 *
 * <p>既存 {@link RecruitmentPaymentRetryBatch} の {@code processRetry} は同一クラス内から自己呼び出しされており、
 * Spring プロキシを経由しないため {@code @Transactional} が実際には効いていない。1 件の失敗が他件を巻き込まない
 * ようにするには、1 件分の処理を別 Bean へ切り出して {@code REQUIRES_NEW} を効かせる必要がある。</p>
 *
 * <p>{@code processChunk} はチャンク単位の一括処理の入口である。escrow の引き当ては
 * {@code sourceParticipantId} の IN 句による一括取得とし、レコード 1 件ごとに個別クエリを撃たない（AC-21）。</p>
 *
 * <p><b>第三陣（試練）時点の状態</b>: 宣言のみ。本体は第四陣（出陣）で実装する。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RecruitmentCancellationFeeRetryProcessor {

    /** リトライ上限（既存バッチと同値・§5.4）。 */
    public static final int MAX_RETRY_COUNT = 3;

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;
    private final ConnectChargeService connectChargeService;

    /**
     * チャンク 1 ページ分をまとめて処理する（escrow は一括取得・N+1 を作らない）。
     *
     * @param chunk 処理対象のキャンセル記録
     * @return 徴収に成功した件数
     */
    public int processChunk(List<RecruitmentCancellationRecordEntity> chunk) {
        throw new UnsupportedOperationException("F03.11.1 リトライの一括処理は第四陣で実装");
    }

    /**
     * 1 件分の徴収リトライを独立したトランザクションで処理する。
     *
     * @param record 対象のキャンセル記録
     * @return 徴収に成功したら true
     */
    public boolean processOne(RecruitmentCancellationRecordEntity record) {
        throw new UnsupportedOperationException("F03.11.1 リトライの 1 件処理は第四陣で実装");
    }
}
