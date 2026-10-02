package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 予約レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ReservationResponse {

    Long id;
    ReservationIdentifierDto identifier;
    SlotSummaryDto slot;
    ReservationStatusDto status;
    CancellationDto cancellation;
    NotesDto notes;
    ReservationAuditDto audit;

    /**
     * 予約グループ要約（F03.4.3 §5.6 #10・additive）。
     * <b>単枠予約（group_id NULL）では null</b> — 既存契約不変。
     * グループ詳細の全量は別 DTO {@code ReservationGroupResponse}（グループ専用 API）が返す。
     */
    GroupSummaryDto group;

    /**
     * 定期予約の series ID（F03.4.5 §6.2 W2-5・additive・<b>一覧/詳細 GET でも常に返る</b>）。
     *
     * <p><b>単発予約では null</b> — 既存契約不変。</p>
     *
     * <p><b>なぜトップレベルに置くか（検分 MUST①）</b>: {@link #recurring} /
     * {@link #recurringCancel} / {@link #recurringConfirm} は「操作の結果明細」であり
     * <b>作成・キャンセル・承認の応答でしか埋まらない</b>（一覧/詳細 GET では常に null）。
     * series 所属を示す情報が結果明細の内側にしか無いと、FE は一覧・詳細から
     * 「この予約は定期予約の一部か」を判定できず、キャンセルスコープ 2 択 UI や
     * series 一括承認ボタンの出し分けが実装不能になる。API 境界は BE 弾で閉じる
     * （{@code feedback_fe_be_parallel_api_boundary_after_generated_types}）。</p>
     */
    UUID recurringSeriesId;

    /**
     * 定期予約（series）の要約と<b>作成時の結果明細</b>（F03.4.5 §6.2 W2-5・additive）。
     * <b>単発予約では null</b> — 既存契約不変。
     *
     * <p>本フィールドは<b>作成応答でのみ</b>埋まる。一覧/詳細 GET から series 所属を知るには
     * トップレベルの {@link #recurringSeriesId} を使う。</p>
     */
    RecurringSeriesDto recurring;

    /**
     * 「以降すべてキャンセル」（{@code THIS_AND_FOLLOWING}）の結果明細（F03.4.5 §6.2 W2-5・additive）。
     * <b>{@code THIS_ONLY}（既定）では null</b> — 既存契約不変。
     */
    RecurringCancelDto recurringCancel;

    /**
     * series 一括承認（{@code scope=SERIES}）の結果明細（F03.4.5 §6.2 W2-5・additive）。
     * <b>単票承認（既定）では null</b> — 既存契約不変。
     */
    RecurringConfirmDto recurringConfirm;

    public record ReservationIdentifierDto(Long reservationSlotId, Long lineId, Long teamId, Long userId, String userName) {}

    public record SlotSummaryDto(String lineName, String title, LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}

    public record ReservationStatusDto(String status, LocalDateTime bookedAt, LocalDateTime confirmedAt, LocalDateTime completedAt) {}

    public record CancellationDto(LocalDateTime cancelledAt, String cancelReason, String cancelledBy) {}

    public record NotesDto(String userNote, String adminNote) {}

    public record ReservationAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}

    /**
     * 予約グループ要約（F03.4.3 §5.6 #10）。一覧で「10:00〜11:00（30分×2）」の 1 件表示に必要な最小情報。
     *
     * @param groupId      予約グループID
     * @param groupSize    グループの枠数（兄弟行数）
     * @param groupEndTime グループ末尾枠の終了時刻（slot.endTime は代表行の枠終了のため別途保持する）
     * @param menuName     メニュー名（削除済みメニューも履歴解決・G-14。メニューなしは null）
     */
    public record GroupSummaryDto(UUID groupId, Integer groupSize, LocalTime groupEndTime, String menuName) {}

    /**
     * 定期予約 1 回分の処理結果（F03.4.5 §6.2）。
     *
     * <p>スキップの明細（{@code skippedWeeks[]}）は設計書 §6.2 の {@code {date, reason}} をそのまま表す。
     * {@code reservationId} は成立した回のみ非 null（スキップ回は null）。</p>
     *
     * <p><b>{@code reason} は enum で露出する（検分 MUST②）</b>: {@code String} で出すと OpenAPI が
     * ただの {@code {"type":"string"}} になり、FE は文字列比較になって網羅性チェック（union 型の
     * exhaustiveness）が効かない。理由が増えたときに FE が気付けるよう
     * {@link RecurringWeekSkipReason} をそのまま型として出す（confirm の {@code scope} が
     * {@code ["THIS_ONLY","SERIES"]} の enum で出ているのと同じ形）。</p>
     *
     * @param date          対象日（起点日 + 7×k）
     * @param reason        スキップ理由（成立した回は null）
     * @param reservationId 対象の予約ID（成立/キャンセル/承認した回、およびスキップ理由が
     *                      既存行に起因する回では非 null。枠が無くて作れなかった回は null）
     */
    public record RecurringWeekOutcomeDto(
            LocalDate date, RecurringWeekSkipReason reason, Long reservationId) {}

    /**
     * 定期予約作成の結果（F03.4.5 §6.2・AC-5-1 / AC-5-13）。
     *
     * @param seriesId     series ID。<b>成立が 1 件だけのときは null</b>（1 行だけの series は発行しない）
     * @param repeatWeeks  リクエストされた繰り返し週数（起点週を含む）
     * @param createdCount 成立した件数（起点週を含む。常に 1 以上）
     * @param skippedCount スキップした件数
     * @param createdWeeks 成立した回の明細（起点週を先頭に日付昇順）
     * @param skippedWeeks スキップした回の明細（日付昇順）
     */
    public record RecurringSeriesDto(
            UUID seriesId,
            Integer repeatWeeks,
            Integer createdCount,
            Integer skippedCount,
            List<RecurringWeekOutcomeDto> createdWeeks,
            List<RecurringWeekOutcomeDto> skippedWeeks) {}

    /**
     * 「以降すべてキャンセル」の結果（F03.4.5 §6.2・AC-5-7）。
     *
     * @param seriesId       対象 series ID
     * @param cancelledCount キャンセルした件数（起点の当該回を含む）
     * @param cancelledWeeks キャンセルした回の明細（日付昇順）
     * @param skippedWeeks   締切超過・状態不整合でスキップした回の明細（日付昇順）
     */
    public record RecurringCancelDto(
            UUID seriesId,
            Integer cancelledCount,
            List<RecurringWeekOutcomeDto> cancelledWeeks,
            List<RecurringWeekOutcomeDto> skippedWeeks) {}

    /**
     * series 一括承認の結果（F03.4.5 §6.2・AC-5-9）。
     *
     * @param seriesId       対象 series ID
     * @param confirmedCount 承認した件数（起点の当該回を含む）
     * @param confirmedWeeks 承認した回の明細（日付昇順）
     * @param skippedWeeks   PENDING でなくスキップした回の明細（日付昇順）
     */
    public record RecurringConfirmDto(
            UUID seriesId,
            Integer confirmedCount,
            List<RecurringWeekOutcomeDto> confirmedWeeks,
            List<RecurringWeekOutcomeDto> skippedWeeks) {}
}
