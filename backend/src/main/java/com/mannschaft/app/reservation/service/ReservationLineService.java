package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.dto.CreateReservationLineRequest;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationLineRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 予約ラインサービス。チームが提供する予約メニュー（ライン）のCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationLineService {

    private final ReservationLineRepository lineRepository;
    private final ReservationMapper reservationMapper;
    /** F03.4.2 §5.5: ライン削除フロー手順1（active テンプレの生成停止）用。 */
    private final com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository templateRepository;
    /** F03.4.2 §5.5: ライン削除フロー手順2（active 予約ガード）・手順3（purge 除外判定）用。 */
    private final com.mannschaft.app.reservation.repository.ReservationRepository reservationRepository;
    /** F03.4.2 §5.5: ライン削除フロー手順3（予約なし未来枠の purge）用。 */
    private final com.mannschaft.app.reservation.repository.ReservationSlotRepository slotRepository;
    /** F03.4.2 §5.5: ライン削除フロー手順4（reservation_menu_lines 行の明示削除・F03.4.1 §3 RESTRICT 対応）用。 */
    private final com.mannschaft.app.reservation.repository.ReservationMenuLineRepository menuLineRepository;
    /** F03.4.2 §5.5: 「今日以降」判定の基準時刻。テストは固定 Clock を注入する。 */
    private final java.time.Clock clock;

    /**
     * 1 チームあたりの予約ライン上限（F03.4.2 §3.4 で 5→20 へ拡張）。
     *
     * <p>業態 P90（飲食テーブル・美容室セット面・整骨院ベッド・共用施設）とマトリックス UI の
     * 実用限界（7日 × 21列 = 147 行）を満たす最小の切りの良い値として 20 で確定（根拠は設計書 §3.4）。</p>
     */
    private static final long MAX_LINES_PER_TEAM = 20L;

    /** display_order の許可範囲（チーム内 1〜20・F03.4.2 §3.4）。 */
    private static final int MIN_DISPLAY_ORDER = 1;
    private static final int MAX_DISPLAY_ORDER = 20;

    /**
     * ライン削除ガード（§5.5 手順2）で「有効な予約」と見なす active ステータス。
     * PENDING / CONFIRMED は将来の来店が期待されている（ReservationSlotService の削除ガードと同一定義）。
     */
    private static final List<com.mannschaft.app.reservation.ReservationStatus> ACTIVE_RESERVATION_STATUSES =
            List.of(com.mannschaft.app.reservation.ReservationStatus.PENDING,
                    com.mannschaft.app.reservation.ReservationStatus.CONFIRMED);

    /**
     * チームの予約ライン一覧を取得する。
     *
     * @param teamId チームID
     * @return 予約ラインレスポンスリスト
     */
    public List<ReservationLineResponse> listLines(Long teamId) {
        List<ReservationLineEntity> lines = lineRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
        return reservationMapper.toLineResponseList(lines);
    }

    /**
     * チームの有効な予約ライン一覧を取得する。
     *
     * @param teamId チームID
     * @return 有効な予約ラインレスポンスリスト
     */
    public List<ReservationLineResponse> listActiveLines(Long teamId) {
        List<ReservationLineEntity> lines = lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(teamId);
        return reservationMapper.toLineResponseList(lines);
    }

    /**
     * 予約ラインを作成する。
     *
     * @param teamId  チームID
     * @param request 作成リクエスト
     * @return 作成された予約ラインレスポンス
     */
    @Transactional
    public ReservationLineResponse createLine(Long teamId, CreateReservationLineRequest request) {
        // ④ ライン上限 5 本。既存のアクティブライン数が上限に達していれば拒否（400）。
        if (lineRepository.countByTeamId(teamId) >= MAX_LINES_PER_TEAM) {
            throw new BusinessException(ReservationErrorCode.LINE_LIMIT_EXCEEDED);
        }
        // display_order を明示指定した場合は 1〜5 の範囲を検証（省略時は既定 1 で範囲内）。
        validateDisplayOrder(request.getDisplayOrder());

        ReservationLineEntity entity = ReservationLineEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 1)
                .defaultStaffUserId(request.getDefaultStaffUserId())
                .build();

        ReservationLineEntity saved = lineRepository.save(entity);
        log.info("予約ライン作成: teamId={}, lineId={}, name={}", teamId, saved.getId(), saved.getName());
        return reservationMapper.toLineResponse(saved);
    }

    /**
     * 予約ラインを更新する。
     *
     * @param teamId  チームID
     * @param lineId  ラインID
     * @param request 更新リクエスト
     * @return 更新された予約ラインレスポンス
     */
    @Transactional
    public ReservationLineResponse updateLine(Long teamId, Long lineId, UpdateReservationLineRequest request) {
        ReservationLineEntity entity = findLineOrThrow(teamId, lineId);

        if (request.getName() != null) {
            entity.changeName(request.getName());
        }
        if (request.getDescription() != null) {
            entity.changeDescription(request.getDescription());
        }
        if (request.getDisplayOrder() != null) {
            validateDisplayOrder(request.getDisplayOrder());
            entity.changeDisplayOrder(request.getDisplayOrder());
        }
        if (request.getIsActive() != null) {
            if (request.getIsActive()) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }
        if (request.getDefaultStaffUserId() != null) {
            entity.changeDefaultStaff(request.getDefaultStaffUserId());
        }

        ReservationLineEntity saved = lineRepository.save(entity);
        log.info("予約ライン更新: teamId={}, lineId={}", teamId, lineId);
        return reservationMapper.toLineResponse(saved);
    }

    /**
     * 予約ラインを論理削除する（F03.4.2 §5.5・精査2パス A1 で再設計された非循環フロー）。
     *
     * <p><b>単一トランザクション内で以下の番号順</b>に実行する。1→2 の順序が本質
     * （生成停止が先・ガードが後）: 旧設計の「未来枠あり→409＋テンプレ停止は成功時副作用」は、
     * テンプレが毎晩枠を生成し続ける限り 409 が常に成立して削除が永遠に不能になる循環デッドロック
     * だったため撤回された。2 で 409 になった場合は tx ロールバックで 1 も巻き戻るが、
     * 管理者が予約を振替 → 再実行すれば 1〜5 が一気に通る（再実行可能）。409 の間に翌晩の生成が
     * 走っても増えるのは「予約のない枠」だけで、次回実行の 3 が purge する —
     * 追いかけっこが構造的に終端する。</p>
     *
     * <ol>
     *   <li><b>生成停止</b>（枠存在ガードより前・第一手順）: 当該ラインの active テンプレを全て is_active=FALSE 化</li>
     *   <li><b>409 ガード</b>（唯一のガード）: 当該ラインに active 予約（PENDING/CONFIRMED）があれば
     *       {@link ReservationErrorCode#LINE_HAS_ACTIVE_RESERVATIONS}（409）。
     *       「予約のない未来のライン軸枠が存在する」ことは 409 事由にしない</li>
     *   <li><b>予約なし未来枠の自動 purge</b>: line_id=対象ライン AND slot_date&gt;=今日 AND active 予約が
     *       紐づかない枠を一括論理削除（予約が紐づく枠・過去枠は履歴として残す）。
     *       論理削除行も uq_rs_template_cell に残るが、テンプレは停止済みのため再生成は起きない。
     *       将来テンプレを再 ON した場合の UNIQUE 衝突は生成側の INSERT IGNORE が吸収する（§5.3）</li>
     *   <li><b>reservation_menu_lines 行の削除</b>: F03.4.1（並行開発）のテーブルの line_id FK が
     *       ON DELETE RESTRICT のため、論理削除前にアプリ層で提供可否行を明示削除する（結線済み）</li>
     *   <li><b>ライン本体の論理削除</b>（deleted_at セット）</li>
     * </ol>
     *
     * @param teamId チームID
     * @param lineId ラインID
     * @throws BusinessException ライン未存在（LINE_NOT_FOUND）/ active 予約あり（LINE_HAS_ACTIVE_RESERVATIONS・409）
     */
    @Transactional
    public void deleteLine(Long teamId, Long lineId) {
        ReservationLineEntity entity = findLineOrThrow(teamId, lineId);

        // 1. 生成停止（★枠存在ガードより前・第一手順）
        List<com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity> activeTemplates =
                templateRepository.findByLineIdAndIsActiveTrue(lineId);
        if (!activeTemplates.isEmpty()) {
            activeTemplates.forEach(
                    com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity::deactivate);
            templateRepository.saveAll(activeTemplates);
        }

        // 2. 409 ガード（唯一のガード・事由を active 予約に限定）
        if (reservationRepository.existsByLineIdAndStatusIn(lineId, ACTIVE_RESERVATION_STATUSES)) {
            throw new BusinessException(ReservationErrorCode.LINE_HAS_ACTIVE_RESERVATIONS);
        }

        // 3. 予約なし未来枠の自動 purge（予約が紐づく枠・過去枠は履歴として残す）
        List<com.mannschaft.app.reservation.entity.ReservationSlotEntity> futureSlots =
                slotRepository.findByLineIdAndSlotDateGreaterThanEqual(lineId, java.time.LocalDate.now(clock));
        if (!futureSlots.isEmpty()) {
            List<Long> slotIds = futureSlots.stream()
                    .map(com.mannschaft.app.reservation.entity.ReservationSlotEntity::getId)
                    .toList();
            java.util.Set<Long> reservedSlotIds = new java.util.HashSet<>(
                    reservationRepository.findSlotIdsWithActiveReservations(slotIds, ACTIVE_RESERVATION_STATUSES));
            List<com.mannschaft.app.reservation.entity.ReservationSlotEntity> purgeable = futureSlots.stream()
                    .filter(slot -> !reservedSlotIds.contains(slot.getId()))
                    .toList();
            purgeable.forEach(com.mannschaft.app.reservation.entity.ReservationSlotEntity::softDelete);
            slotRepository.saveAll(purgeable);
            log.info("ライン削除に伴う未来枠purge: teamId={}, lineId={}, purged={}/{}",
                    teamId, lineId, purgeable.size(), futureSlots.size());
        }

        // 4. reservation_menu_lines 行の削除（F03.4.1 §3 の RESTRICT 判断に対応・同一 tx）。
        //    line_id の FK は ON DELETE RESTRICT のため、論理削除前にアプリ層で提供可否行を明示削除する。
        //    行削除後にメニューの提供可否行が 0 件になった場合の意味論は F03.4.1 §3 の既定
        //    （0 件 = 全ラインで提供可）に自然に合流する。
        menuLineRepository.deleteByLineId(lineId);

        // 5. ライン本体の論理削除
        entity.softDelete();
        lineRepository.save(entity);
        log.info("予約ライン削除: teamId={}, lineId={}", teamId, lineId);
    }

    /**
     * display_order がチーム内許可範囲（1〜5）かを検証する。null は呼び出し側で除外済み前提。
     *
     * @param displayOrder 検証対象の表示順
     * @throws BusinessException 範囲外（INVALID_DISPLAY_ORDER・400）
     */
    private void validateDisplayOrder(Integer displayOrder) {
        if (displayOrder != null && (displayOrder < MIN_DISPLAY_ORDER || displayOrder > MAX_DISPLAY_ORDER)) {
            throw new BusinessException(ReservationErrorCode.INVALID_DISPLAY_ORDER);
        }
    }

    /**
     * 予約ラインを取得する。存在しない場合は例外をスローする。
     */
    private ReservationLineEntity findLineOrThrow(Long teamId, Long lineId) {
        return lineRepository.findByIdAndTeamId(lineId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.LINE_NOT_FOUND));
    }
}
