package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
@RequiredArgsConstructor
public class ReservationRecurringSlotResolver {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private final ReservationSlotRepository slotRepository;
    /** 機能B: 単発の予約不可枠（対象期間ぶんを 1 クエリで先読み）。 */
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    /** F03.4.5 §4: 定期予約不可枠の active ルール（チーム単位 1 クエリ・最大 50 行）。 */
    private final ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;
    /** 単発＋定期を 1 本で判定する共有ユーティリティ（別実装厳禁・§4.2）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    private final ReservationRepository reservationRepository;

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
        if (repeatWeeks <= 1) {
            return List.of();
        }
        LocalDate baseDate = baseSlot.getSlotDate();
        LocalDate from = baseDate.plusWeeks(1);
        LocalDate to = baseDate.plusWeeks(repeatWeeks - 1L);

        // ① 候補枠: slot_date の範囲検索 1 回（週ごとに投げない・AC-5-10）。
        //    範囲検索なので同一時間帯の「曜日違い」の枠も混ざって返る。7 の倍数日でない枠は
        //    ここで捨てる（拾うと「毎週同じ曜日」という利用者の期待を壊す）。
        List<ReservationSlotEntity> candidates = slotRepository.findRecurringCandidateSlots(
                teamId, from, to, baseSlot.getStartTime(), baseSlot.getEndTime());
        Set<LocalDate> targetDates = new LinkedHashSet<>();
        for (int k = 1; k < repeatWeeks; k++) {
            targetDates.add(baseDate.plusWeeks(k));
        }
        Map<LocalDate, ReservationSlotEntity> slotByDate = new LinkedHashMap<>();
        for (ReservationSlotEntity slot : candidates) {
            if (!targetDates.contains(slot.getSlotDate())) {
                continue;
            }
            // 「同一ライン」は起点枠との完全一致で判定する（共通枠 null 同士も一致とみなす）。
            if (!Objects.equals(slot.getLineId(), baseSlot.getLineId())) {
                continue;
            }
            // クエリは (slot_date, id) 昇順なので、同日に複数枠があれば最小 id を採る（決定的）。
            slotByDate.putIfAbsent(slot.getSlotDate(), slot);
        }

        // ② 単発予約不可枠: 同じ範囲を 1 回。
        List<ReservationBlockedTimeEntity> blocks = blockedTimeRepository
                .findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(teamId, from, to);
        // ③ 定期予約不可枠: チーム単位 1 回（active 最大 50 行のメモリ突合）。
        List<ReservationRecurringBlockedTimeEntity> recurringRules =
                recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(teamId);
        // ④ 自分の既存 active 予約: 候補枠 ID をまとめて 1 回（候補ゼロなら空 IN 句を投げない）。
        Set<Long> alreadyReserved = slotByDate.isEmpty()
                ? Set.of()
                : new HashSet<>(reservationRepository.findSlotIdsAlreadyReservedByUser(
                        userId,
                        slotByDate.values().stream().map(ReservationSlotEntity::getId).toList(),
                        ACTIVE_STATUSES));

        List<WeekCandidate> outcomes = new ArrayList<>(repeatWeeks - 1);
        for (LocalDate date : targetDates) {
            ReservationSlotEntity slot = slotByDate.get(date);
            if (slot == null) {
                outcomes.add(new WeekCandidate(date, null, RecurringWeekSkipReason.NOT_GENERATED));
                continue;
            }
            outcomes.add(new WeekCandidate(
                    date, slot.getId(), skipReasonOf(slot, blocks, recurringRules, alreadyReserved)));
        }
        return outcomes;
    }

    /**
     * 枠 1 件のスキップ理由を判定する（予約可能なら {@code null}）。
     *
     * <p>判定順は設計書 §6.2 の列挙順（枠なし → FULL → 予約不可枠）に、実装上必要な
     * CLOSED（受付停止）と二重予約を加えたもの。順序を固定しないと同一状況で返る理由が
     * 実行ごとに変わり、FE の文言が非決定的になる。</p>
     */
    private RecurringWeekSkipReason skipReasonOf(
            ReservationSlotEntity slot,
            List<ReservationBlockedTimeEntity> blocks,
            List<ReservationRecurringBlockedTimeEntity> recurringRules,
            Set<Long> alreadyReserved) {
        if (slot.getSlotStatus() == SlotStatus.CLOSED) {
            // 管理者が明示的に閉じた枠。FULL に丸めると「満席」という嘘を会員に伝えることになる。
            return RecurringWeekSkipReason.CLOSED;
        }
        if (slot.getSlotStatus() == SlotStatus.FULL) {
            return RecurringWeekSkipReason.FULL;
        }
        // slot_status が追いついていない枠（capacity 到達済みだが AVAILABLE のまま）も満席として扱う。
        // ここで見逃すと確保 UPDATE が 0 行になり、結局 FULL としてスキップされる（無駄な UPDATE）。
        if (slot.getCapacity() != null && slot.getBookedCount() != null
                && slot.getBookedCount() >= slot.getCapacity()) {
            return RecurringWeekSkipReason.FULL;
        }
        // 単発＋定期を 1 本で判定する共有ユーティリティ（別実装厳禁・§4.2 / AC-5-12）。
        if (unavailabilityChecker.isBlockedByAny(slot, blocks, recurringRules)) {
            return RecurringWeekSkipReason.BLOCKED;
        }
        if (alreadyReserved.contains(slot.getId())) {
            return RecurringWeekSkipReason.ALREADY_RESERVED;
        }
        return null;
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
