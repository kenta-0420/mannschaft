package com.mannschaft.app.receipt.service;

import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.entity.ReceiptNumberSequenceEntity;
import com.mannschaft.app.receipt.repository.ReceiptNumberSequenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 領収書番号の採番サービス（F08.12 §3.2）。
 *
 * <p>従来の採番は発行者設定行そのものを {@code PESSIMISTIC_WRITE} でロックしていた。
 * PLATFORM は全プラットフォームで 1 行しか無いため、月次一括発行が全件直列化する。
 * 本サービスは採番専用表の行だけをロックし、さらに短命トランザクション
 * （{@code REQUIRES_NEW}）に閉じることで、レコード本体を書く親トランザクションの
 * ロック保持時間から切り離す。</p>
 *
 * <p><b>反面教師</b>: {@code MonthlyInvoiceBatchService#generateInvoiceNumber} は
 * {@code count() + 1} で採番しており、並行実行すればほぼ確実に重複する。同じ轍は踏まない。</p>
 *
 * <p><b>欠番を許容する</b>: レンジ確保後に本体トランザクションがロールバックすると番号が飛ぶ。
 * インボイス制度・電子帳簿保存法とも連番の連続性は要件ではない（重複禁止・改ざん禁止が要件）
 * ため、これは設計上の明示的な選択である。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptNumberSequenceService {

    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** PLATFORM 領収書番号のプレフィックス（例: {@code PLAT-202609-00001}）。 */
    public static final String PLATFORM_NUMBER_PREFIX = "PLAT";

    private final ReceiptNumberSequenceRepository sequenceRepository;

    /** 発行日から期間キー（{@code YYYYMM}）を作る。 */
    public static String periodKeyOf(LocalDate date) {
        return date.format(PERIOD_KEY_FORMAT);
    }

    /**
     * 番号レンジを確保し、開始番号を返す。
     *
     * <p>{@code REQUIRES_NEW} により、本メソッドの戻り時点でロックは解放されている。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID（PLATFORM は 0）
     * @param periodKey 期間キー（{@code YYYYMM}）
     * @param count     確保する個数
     * @return 確保したレンジの開始番号
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reserveRange(ReceiptScopeType scopeType, Long scopeId, String periodKey, int count) {
        ReceiptNumberSequenceEntity sequence = sequenceRepository
                .findForUpdate(scopeType, scopeId, periodKey)
                .orElseGet(() -> createSequence(scopeType, scopeId, periodKey));
        int start = sequence.reserve(count);
        sequenceRepository.saveAndFlush(sequence);
        return start;
    }

    /**
     * 採番行を作る。並行して同じ行を作ろうとした側は {@code uq_rns_scope_period} で落ちるため、
     * 例外を捕捉して勝者の行を読み直す（握りつぶさず DEBUG に残す）。
     */
    private ReceiptNumberSequenceEntity createSequence(
            ReceiptScopeType scopeType, Long scopeId, String periodKey) {
        try {
            return sequenceRepository.saveAndFlush(ReceiptNumberSequenceEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .periodKey(periodKey)
                    .nextNumber(1)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.debug("採番行の作成が並行実行と競合したため勝者の行を読み直す scopeType={} scopeId={} periodKey={}",
                    scopeType, scopeId, periodKey);
            return sequenceRepository.findForUpdate(scopeType, scopeId, periodKey)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 確保した番号を PLATFORM 領収書番号の文字列へ整形する（例: {@code PLAT-202609-00001}）。
     */
    public static String formatPlatformNumber(String periodKey, int number) {
        return String.format("%s-%s-%05d", PLATFORM_NUMBER_PREFIX, periodKey, number);
    }
}
