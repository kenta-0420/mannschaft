package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.GridCellState;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 複数予約対象の空きグリッド（機能C・§4.C）レスポンスDTO。
 *
 * <p>列＝予約対象（スタッフ・共通）、各セル＝時間帯の状態。単日（{@code date}）のみを保持する
 * 2 次元構造（週タブは FE が単日 API を 7 日分呼んで構成する）。</p>
 *
 * <p><b>予約者 PII 非露出（§4.C / C-4）:</b> {@code BOOKED} セルは「埋まっている」ことのみを表し、
 * 予約者氏名 / userId / 予約詳細を<b>構造的に一切持たない</b>（下記 {@link GridCellDto} にそれらの
 * フィールドが存在しないことで BE として担保する。FE のフィルタ任せにしない）。管理用の enrich
 * （氏名込み）は {@code BlockedTimeImpactResponse} 等の別 DTO で扱う。</p>
 */
@Builder(toBuilder = true)
@Getter
public class ReservationGridResponse {

    /** 対象日（単日）。 */
    private final LocalDate date;

    /** 列（予約対象）一覧。スタッフ列＋（該当あれば）共通列。 */
    private final List<GridColumnDto> columns;

    /**
     * グリッドの 1 列（予約対象）。
     *
     * @param staffUserId 予約対象スタッフの user_id。共通列（店共通枠の集約）は {@code null}
     * @param staffName   スタッフ表示名（NameResolver で一括解決）。共通列や解決不能時は {@code null}
     * @param lineIds     その列の {@code staffUserId} を {@code default_staff_user_id} に持つ active
     *                    （{@code is_active=TRUE} かつ {@code deleted_at IS NULL}）な予約ラインの ID 集合。
     *                    共通列は常に空配列。当該スタッフを既定担当に持つラインが 0 本なら空配列
     * @param cells       時間帯セル一覧（開始時刻昇順）
     */
    public record GridColumnDto(
            Long staffUserId,
            String staffName,
            List<Long> lineIds,
            List<GridCellDto> cells) {}

    /**
     * グリッドの 1 セル（時間帯 × 状態）。
     *
     * <p><b>予約者 PII を構造的に含めない</b>（C-4）。userId / userName / reservation 等のフィールドは
     * 意図的に持たない。</p>
     *
     * @param slotId    予約枠ID（この ID から {@code getSlot} を引いても PII は露出しない・§4.C）
     * @param startTime 枠の開始時刻
     * @param endTime   枠の終了時刻
     * @param state     セル状態（{@link GridCellState}）
     * @param price     予約料金（表示用・{@code null} 可）。枠のメタデータであり PII ではない
     */
    public record GridCellDto(
            Long slotId,
            LocalTime startTime,
            LocalTime endTime,
            GridCellState state,
            BigDecimal price) {}
}
