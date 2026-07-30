package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 定期予約（F03.4.5 §6.2 W2-5）の<b>週次枠解決</b>を担うコンポーネント。
 *
 * <p>起点予約の {@code (lineId, dayOfWeek, startTime, endTime)} を保ったまま
 * {@code slot_date + 7k}（k = 1..repeatWeeks-1）の枠を解決し、各週を
 * 「予約できる枠」か「スキップ（理由つき）」に振り分ける。</p>
 *
 * <h2>クエリ本数を週数に比例させない（AC-5-10）</h2>
 * <p>解決は<b>固定本数のクエリ</b>で行う（週数が 2 でも 12 でも同数）:</p>
 * <ol>
 *   <li>候補枠 — {@code slot_date} の<b>範囲検索 1 回</b>
 *       （{@code ReservationSlotRepository#findRecurringCandidateSlots}）</li>
 *   <li>単発予約不可枠 — 同じ範囲を 1 回</li>
 *   <li>定期予約不可枠 — チーム単位 1 回（active ルール最大 50 行）</li>
 *   <li>自分の既存 active 予約 — 候補枠 ID をまとめて 1 回（候補ゼロなら発行しない）</li>
 * </ol>
 * <p>これを「週ごとに 1 日ずつ引く」実装にすると、会員の予約作成というホットパスに
 * 12 クエリの N+1 が入る。</p>
 *
 * <h2>スキップ理由の判定順序</h2>
 * <p>設計書 §6.2 の列挙順（「枠が存在しない」→「FULL」→「予約不可枠 overlap」）に従う。
 * さらに実装上必要な CLOSED / 二重予約を後段に加える。順序を決めておかないと
 * 同一状況で返る理由が実行ごとに変わり、FE の文言が非決定的になる。</p>
 */
@Component
public class ReservationRecurringSlotResolver {

    /**
     * 週次の枠解決を行う。
     *
     * @param teamId      チームID
     * @param userId      予約者ユーザーID（二重予約週の判定に使う）
     * @param baseSlot    起点枠（この枠の日付・時間帯・ラインが繰り返しの基準になる）
     * @param repeatWeeks 繰り返し週数（<b>起点週を含む</b>。2 以上を想定）
     * @return 2 週目以降の解決結果（日付昇順・起点週は含まない）
     */
    public List<WeekCandidate> resolve(
            Long teamId, Long userId, ReservationSlotEntity baseSlot, int repeatWeeks) {
        throw new UnsupportedOperationException("F03.4.5 §6.2 W2-5: 未実装（実装コミットで green 化する）");
    }

    /**
     * 1 週分の解決結果。
     *
     * <p>{@code skipReason == null} のときだけ予約を試みる。{@code slotId} は枠が見つかった場合のみ非 null
     * （{@link RecurringWeekSkipReason#NOT_GENERATED} では null）。</p>
     *
     * @param date       対象日（起点日 + 7×k）
     * @param slotId     解決できた枠 ID（枠が無い週は null）
     * @param skipReason スキップ理由（予約可能な週は null）
     */
    public record WeekCandidate(LocalDate date, Long slotId, RecurringWeekSkipReason skipReason) {

        /** 予約を試みるべき週か。 */
        public boolean bookable() {
            return skipReason == null && slotId != null;
        }
    }
}
