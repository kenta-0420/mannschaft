package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;

/**
 * 予約不可枠（機能B・§5.B）の時間帯重複判定を一箇所に集約した<b>単一 overlap ユーティリティ</b>。
 *
 * <p>設計書 §5.B「単一 overlap ユーティリティ（別実装厳禁）」に従い、以下の 3 箇所が
 * <b>同一実装を共有</b>する:</p>
 * <ol>
 *   <li>{@code ReservationSlotService.listAvailableSlots}（空き枠一覧からの除外）</li>
 *   <li>{@code ReservationService.createReservation}（予約作成の拒否 = RESERVATION_009）</li>
 *   <li>{@code ReservationGridService}（機能C グリッドのセル {@code UNAVAILABLE} 判定・後続 PR）</li>
 * </ol>
 *
 * <h2>判定ルール（§5.B）</h2>
 * <pre>
 * isBlocked(slot, blockedTime):
 *   if slot.slotDate != blockedTime.blockedDate: return false
 *   # 対象軸マッチ
 *   if resourceType == TEAM:   resourceMatch = true
 *   elif resourceType == STAFF: resourceMatch = (resourceId == slot.staffUserId)
 *   else:                       resourceMatch = false   # LINE/RESOURCE は MVP 未 enforce
 *   if not resourceMatch: return false
 *   # 時間帯 overlap（半開区間）
 *   if blockedStart == null and blockedEnd == null: return true   # 全日
 *   return slot.start < blocked.end and blocked.start < slot.end
 * </pre>
 *
 * <p><b>半開区間</b>: {@code slot.start == blocked.end}（隣接）は<b>非重複</b>として扱う（境界を潰さない）。</p>
 */
@Component
public class ReservationUnavailabilityChecker {

    /**
     * slot（日付・時間帯・担当スタッフ）が予約不可枠に該当するかを判定する（コア・§5.B）。
     * 予約不可枠の仕様を「エンティティではなく生値」で受けることで、<b>まだ永続化されていない
     * 提案枠</b>（作成/更新の 409 ガード・impact）と、<b>既存の予約不可枠エンティティ</b>の
     * 両方から同一ロジックを共有できる。
     *
     * @param slotDate         slot の日付
     * @param slotStart        slot の開始時刻
     * @param slotEnd          slot の終了時刻
     * @param slotStaffUserId  slot の担当スタッフ user_id（共通枠は null）
     * @param blockedDate      予約不可枠の日付
     * @param blockedStart     予約不可枠の開始時刻（全日は null）
     * @param blockedEnd       予約不可枠の終了時刻（全日は null）
     * @param resourceType     予約不可枠の対象軸
     * @param resourceId       STAFF 軸のときの対象スタッフ user_id（TEAM 時は無視）
     * @return 該当（ブロック対象）なら true
     */
    public boolean isBlocked(
            LocalDate slotDate, LocalTime slotStart, LocalTime slotEnd, Long slotStaffUserId,
            LocalDate blockedDate, LocalTime blockedStart, LocalTime blockedEnd,
            ReservationBlockedResourceType resourceType, Long resourceId) {

        if (slotDate == null || blockedDate == null || !slotDate.equals(blockedDate)) {
            return false;
        }

        boolean resourceMatch;
        if (resourceType == ReservationBlockedResourceType.TEAM) {
            resourceMatch = true;
        } else if (resourceType == ReservationBlockedResourceType.STAFF) {
            // 共通枠（staff_user_id=null）は STAFF ブロックの対象外（null 同士は一致とみなさない）。
            resourceMatch = resourceId != null && resourceId.equals(slotStaffUserId);
        } else {
            // LINE / RESOURCE は MVP 未 enforce。
            resourceMatch = false;
        }
        if (!resourceMatch) {
            return false;
        }

        // 全日ブロック（両 NULL）はその日・その軸の全 slot に該当。
        if (blockedStart == null && blockedEnd == null) {
            return true;
        }
        // 部分ブロックは半開区間で判定（隣接は非重複）。
        return slotStart.isBefore(blockedEnd) && blockedStart.isBefore(slotEnd);
    }

    /**
     * slot エンティティが予約不可枠エンティティに該当するかを判定する（コアへの委譲）。
     *
     * @param slot    予約枠エンティティ
     * @param blocked 予約不可枠エンティティ
     * @return 該当なら true
     */
    public boolean isBlocked(ReservationSlotEntity slot, ReservationBlockedTimeEntity blocked) {
        return isBlocked(
                slot.getSlotDate(), slot.getStartTime(), slot.getEndTime(), slot.getStaffUserId(),
                blocked.getBlockedDate(), blocked.getStartTime(), blocked.getEndTime(),
                blocked.getResourceType(), blocked.getResourceId());
    }

    /**
     * slot が与えられた予約不可枠のいずれか 1 件でも該当するか（§5.B「いずれか 1 件でも真ならブロック」）。
     *
     * @param slot    予約枠エンティティ
     * @param blocks  対象日を含む予約不可枠の集合
     * @return いずれか 1 件でも該当すれば true
     */
    public boolean isBlockedByAny(ReservationSlotEntity slot, Collection<ReservationBlockedTimeEntity> blocks) {
        return blocks.stream().anyMatch(b -> isBlocked(slot, b));
    }
}
