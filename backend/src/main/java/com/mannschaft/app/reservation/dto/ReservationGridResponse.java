package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.GridCellState;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 複数予約対象の空きグリッド（機能C・§4.C / F03.4.4 §4.1 拡張）レスポンスDTO。
 *
 * <p>列＝予約対象（スタッフ・共通、または {@code axis=LINE} 時はライン・共通）、各セル＝時間帯の状態。
 * 単日呼び（{@code date=}）では {@code date}/{@code columns} が非 null・{@code days} は null。
 * レンジ呼び（{@code from}/{@code to}）では {@code days[]} が非 null・{@code date}/{@code columns} は null
 * （完全後方互換 — 既存の単日契約は無変更・F03.4.4 §4.1 契約表）。</p>
 *
 * <p><b>予約者 PII 非露出（§4.C / C-4・F03.4.4 でも全面踏襲）:</b> {@code BOOKED} セルは「埋まっている」
 * ことのみを表し、予約者氏名 / userId / 予約詳細を<b>構造的に一切持たない</b>（下記 {@link GridCellDto} に
 * それらのフィールドが存在しないことで BE として担保する。FE のフィルタ任せにしない）。管理用の enrich
 * （氏名込み）は {@code BlockedTimeImpactResponse} 等の別 DTO で扱う。{@code axis=LINE}/{@code days[]}
 * でも同一の {@link GridCellDto} を共有するため PII は構造的に不在のまま（H-6）。</p>
 */
@Builder(toBuilder = true)
@Getter
public class ReservationGridResponse {

    /** 対象日（単日呼びのときのみ非 null。レンジ呼びでは null）。 */
    private final LocalDate date;

    /** 列（予約対象）一覧（単日呼びのときのみ非 null。レンジ呼びでは null）。 */
    private final List<GridColumnDto> columns;

    /**
     * 応答の列軸（{@code "STAFF"}（既定）/ {@code "LINE"}）。非 null（F03.4.4 新設・additive）。
     * 既存クライアントは未参照でも壊れない。
     */
    private final String axis;

    /** メニューフィルターのメタ情報。{@code menuId} 指定時のみ非 null（F03.4.4 §4.1）。 */
    private final GridMetaDto meta;

    /** 日付レンジ応答（{@code from}/{@code to} 指定時のみ非 null。各要素は単日の {@code columns} と同構造）。 */
    private final List<GridDayDto> days;

    /**
     * メニューフィルターのメタ情報（F03.4.4 §4.1）。
     *
     * <p>{@code requiredCellCount}（本 API・grid 文脈）と {@code requiredSlotCount}
     * （F03.4.1 メニュー一覧）は<b>同一値の文脈別名</b>で、いずれも BE が
     * {@code durationMinutes / 30} から導出する（三者が食い違ったら BE 導出が正）。</p>
     *
     * @param menuId            フィルター対象メニューID
     * @param menuName          メニュー名（表示用）
     * @param requiredCellCount 必要枠数（{@code durationMinutes / 30}・BE 導出。FE の連続空き網掛けに使う）
     * @param cellMinutes       1 セルの分数（固定 30・FE のマジックナンバー排除）
     */
    public record GridMetaDto(
            UUID menuId,
            String menuName,
            int requiredCellCount,
            int cellMinutes) {}

    /**
     * レンジ応答の 1 日分（F03.4.4 §4.1）。{@code columns} は単日応答の {@code columns} と同構造。
     *
     * @param date    対象日
     * @param columns 列（予約対象）一覧
     */
    public record GridDayDto(
            LocalDate date,
            List<GridColumnDto> columns) {}

    /**
     * グリッドの 1 列（予約対象）。
     *
     * @param staffUserId 予約対象スタッフの user_id。共通列（店共通枠の集約）は {@code null}。
     *                    {@code axis=LINE} では常に {@code null}
     * @param staffName   スタッフ表示名（NameResolver で一括解決）。共通列や解決不能時は {@code null}。
     *                    {@code axis=LINE} では常に {@code null}
     * @param lineId      予約対象ラインの ID（F03.4.4）。<b>{@code axis=LINE} のとき非 null</b>
     *                    （共通枠列は {@code null}）。{@code axis=STAFF} では常に {@code null}
     * @param lineName    予約対象ライン名（設備名であり PII ではない・§6）。null 規則は {@code lineId} と同一
     * @param lineIds     その列の {@code staffUserId} を {@code default_staff_user_id} に持つ active
     *                    （{@code is_active=TRUE} かつ {@code deleted_at IS NULL}）な予約ラインの ID 集合。
     *                    共通列・{@code axis=LINE} の列は常に空配列
     * @param cells       時間帯セル一覧（開始時刻昇順）
     */
    public record GridColumnDto(
            Long staffUserId,
            String staffName,
            Long lineId,
            String lineName,
            List<Long> lineIds,
            List<GridCellDto> cells) {}

    /**
     * グリッドの 1 セル（時間帯 × 状態）。
     *
     * <p><b>予約者 PII を構造的に含めない</b>（C-4）。userId / userName / reservation 等のフィールドは
     * 意図的に持たない。</p>
     *
     * @param slotId            予約枠ID（この ID から {@code getSlot} を引いても PII は露出しない・§4.C）
     * @param startTime         枠の開始時刻
     * @param endTime           枠の終了時刻
     * @param state             セル状態（{@link GridCellState}）
     * @param price             予約料金（表示用・{@code null} 可）。枠のメタデータであり PII ではない
     * @param unavailableReason {@code state=UNAVAILABLE} かつ判定元が {@code is_public=TRUE} の定期予約不可枠
     *                          （F03.4.5 §4）のときのみ事由ラベルを載せる（{@code null} 可）。単発 blocked_times
     *                          （常に非公開）由来・is_public=FALSE の定期ルール由来はいずれも {@code null}。
     *                          単発と public 定期ルールが同一セルに重畳する場合も非公開優先で {@code null}
     *                          （§4.4・「休業日なのに研修と誤案内」を防ぐ）
     */
    public record GridCellDto(
            Long slotId,
            LocalTime startTime,
            LocalTime endTime,
            GridCellState state,
            BigDecimal price,
            String unavailableReason) {}
}
