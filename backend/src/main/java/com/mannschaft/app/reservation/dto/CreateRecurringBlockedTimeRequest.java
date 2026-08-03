package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ReservationDayOfWeek;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * 定期予約不可枠 作成リクエストDTO（F03.4.5 §4.6）。
 *
 * <p>親 §4.B の Jackson 手本（{@code @Getter @RequiredArgsConstructor}＋全 final＋単一コンストラクタ）に倣う
 * （{@code feedback_dto_all_final_multi_constructor_jackson_no_creators}）。</p>
 *
 * <p>全日型（start/end 欠落）は許可しない（{@code @NotNull} で 400・§4.3）。時刻の30分グリッド・
 * {@code start < end} 検証は既存 {@code SlotTimeValidator}（007/022 再利用）を Service 層で適用する。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateRecurringBlockedTimeRequest {

    /** 対象ライン。NULL = チーム全体。不正 ID は 400（LINE_NOT_FOUND=001 再利用）。 */
    private final Long lineId;

    /**
     * 曜日。<b>3文字大文字 {@code MON}..{@code SUN} のみ</b>。
     * 不正値（{@code MONDAY}/小文字/その他）は Jackson の enum デシリアライズ失敗で 400。
     */
    @NotNull
    private final ReservationDayOfWeek dayOfWeek;

    /** 開始（30分単位）。全日型は許可しないため必須（§4.3）。 */
    @NotNull
    private final LocalTime startTime;

    /** 終了（30分単位・start より後）。全日型は許可しないため必須（§4.3）。 */
    @NotNull
    private final LocalTime endTime;

    /** 事由ラベル（必須・100文字以内・§4.1）。 */
    @NotBlank
    @Size(max = 100)
    private final String reason;

    /** TRUE = 会員のマトリックス該当セルに reason を表示。未指定（null）は Service 層で FALSE に正規化する。 */
    private final Boolean isPublic;

    /**
     * 衝突する既存予約を強行キャンセルして登録するか（F03.4.5 §6.2 W2-5・殿の裁定 2026-07-30・additive）。
     *
     * <p>未指定（null）/ FALSE = <b>従来どおり</b>。overlap する active 予約が 1 件でもあれば
     * 409（{@code RESERVATION_027}）で拒否する（挙動不変）。</p>
     *
     * <p>TRUE = 90 日 horizon 内で overlap する active 予約を一括 CANCELLED
     * （{@code cancelledBy=ADMIN}・事由は定型文）にし、<b>各申込者へ通知してから</b>ルールを登録する。</p>
     *
     * <h2>なぜこのフラグが必要か（機能の構造的破綻の根治）</h2>
     * <p>§4.3 の 409 ガードは「今日から 90 日先までに active な予約があれば拒否」する。一方
     * §6.2 の定期予約は最大 12 週 = <b>約 84 日分</b>の予約を並べる。したがって
     * <b>会員 1 人が定期予約を入れるだけで、管理者は「毎週火曜19時は研修」を恒久的に登録できなくなる</b>。
     * 「409 のまま運用で回避」は不可という裁定が下りており、対処療法ではなく
     * 「管理者が影響を把握したうえで既存予約を整理して登録できる」正式な導線を用意することが根治である。
     * 管理者は事前に {@code GET .../impact}（副作用ゼロ・氏名込み一覧）で件数と対象を確認できる。</p>
     */
    private final Boolean forceCancelConflicting;
}
