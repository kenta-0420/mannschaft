package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * 週間テンプレートからの 30 分セル枠生成の<b>単一実装</b>（F03.4.2 §5.1/§5.2）。
 *
 * <p>手動 generate（{@code ReservationSlotTemplateService}）と日次バッチ
 * （{@code ReservationSlotGenerationBatchService}）の両方がここを呼ぶ（別実装厳禁）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationSlotGenerationService {

    private final ReservationSlotTemplateRepository templateRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationBusinessHourRepository businessHourRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /**
     * 手動 generate（F03.4.2 §4/§5.2）: チームの active テンプレ全件を対象に
     * 明日〜horizon（weeks*7 日先）までの枠を冪等生成する。
     *
     * @param teamId    チームID
     * @param weeks     何週先まで（1〜4・null は 4）
     * @param createdBy 実行者（生成枠の created_by へ）
     * @return 生成結果カウント
     * @throws BusinessException active テンプレが 0 件（400・汎用）
     */
    public GenerateSlotsResponse generateForTeam(Long teamId, Integer weeks, Long createdBy) {
        throw new UnsupportedOperationException("未実装（試練 red・出陣で green 化）");
    }

    /**
     * 日次バッチ用の差分生成（F03.4.2 §5.4）: テンプレ 1 行ごとに差分レンジ
     * {@code [max(tomorrow, MAX(slot_date)+1), tomorrow+27日]} を計算して生成する。
     *
     * @param teamId チームID
     * @return 生成結果カウント（active テンプレ 0 件は全カウント 0 で正常return・バッチはエラーにしない）
     */
    public GenerateSlotsResponse generateDiffForTeam(Long teamId) {
        throw new UnsupportedOperationException("未実装（試練 red・出陣で green 化）");
    }
}
