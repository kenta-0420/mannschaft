package com.mannschaft.app.reservation;

/**
 * 予約キャンセルの適用範囲（F03.4.5 §6.2 W2-5・定期予約の「この回のみ / 以降すべて」）。
 *
 * <p><b>語彙は新規発明しない</b>: schedule ドメインの繰り返し予定編集
 * （{@code ScheduleRecurrenceService} / {@code PersonalScheduleService} の
 * {@code THIS_ONLY} / {@code THIS_AND_FOLLOWING}）と<b>同一の語彙</b>を用いる。
 * ユーザーが 1 つのアプリ内で「この回のみ／以降すべて」という同じ選択に別の言葉を見ることを防ぐ。
 * クラス自体は共有しない（ドメイン境界の原則: 異なるドメインの型を直接参照しない）。</p>
 *
 * <p>schedule ドメインは {@code ALL}（全ての回）も持つが、<b>予約では採用しない</b>。
 * 過去の来店実績を遡って消す操作は履歴・売上の整合を壊すため、
 * 「当該日以降」に限定するのが予約ドメインの正しい表現である（§6.2 / AC-5-7）。</p>
 */
public enum ReservationCancelScope {

    /** この回のみキャンセルする（既定）。series に属さない単発予約も常にこの扱い。 */
    THIS_ONLY,

    /**
     * 当該回<b>以降</b>の同一 series の予約をキャンセルする（過去回は不変）。
     *
     * <p>各行に既存のキャンセル検証（締切・状態）を適用し、通らない回はスキップして明細を返す。
     * 対象は<b>自分が所有する行のみ</b>（他人の行は series ID を知っていてもキャンセルできない・AC-5-8）。</p>
     */
    THIS_AND_FOLLOWING
}
