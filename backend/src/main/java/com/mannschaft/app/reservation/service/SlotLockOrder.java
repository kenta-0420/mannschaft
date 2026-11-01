package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.entity.ReservationSlotEntity;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 複数の予約枠の {@code booked_count} を更新するときの<b>行ロック取得順</b>を一つに揃える
 * （CMP-260822-1730 AC-1730-6 / 検分 r3 P2）。
 *
 * <p>{@code decrementAndReopen} / {@code incrementAndCheckFull} はアトミック UPDATE で枠行の行ロックを取る。
 * 仮押さえ失効バッチは枠を {@code ORDER BY id} で一括ロックしてから減算するため、他の経路が開始時刻順や
 * 予約 ID 順で枠を更新すると、同じ 2 枠を逆順に待ち合う循環（デッドロック）が成立する。
 * 複数枠を更新する全経路は、本クラスで<b>枠 ID 昇順</b>に並べてから更新する。</p>
 *
 * <p>同じ枠が複数回現れる場合（同一枠の複数予約を取り消すなど）は重複を残す。減算回数は予約の件数と
 * 一致させなければならないため、ここでは並べ替えだけを行う（安定ソート）。</p>
 */
final class SlotLockOrder {

    private SlotLockOrder() {
    }

    /** 枠 ID 昇順に並べ替えた新しいリストを返す（重複は保持・安定ソート）。 */
    static List<ReservationSlotEntity> byIdAsc(Collection<ReservationSlotEntity> slots) {
        return slots.stream()
                .sorted(Comparator.comparing(ReservationSlotEntity::getId))
                .toList();
    }
}
