package com.mannschaft.app.timetable.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.timetable.WeekPattern;
import lombok.Getter;

/**
 * F03.15 Phase 4: チーム時間割コマの共通メモ（{@code timetable_slots.notes}）が更新された
 * ときに発行されるイベント。
 *
 * <p>購読者: {@code TeamSlotNoteNotifyListener}（{@code AFTER_COMMIT} + {@code @Async("event-pool")}）。
 * デバウンス（5分）後にチームメンバー個別の {@code personal_timetable_settings.notify_team_slot_note_updates}
 * を確認のうえプッシュ通知する。</p>
 *
 * <p>Issue #2997 / CMP-260827-1152 G7-a（M8）: {@code replaceSlots} はスロットを毎回削除再作成して {@code slotId}
 * が変わるため、デバウンスの単位を slotId ではなく<b>コマの位置</b>
 * （{@code timetableId + dayOfWeek + periodNumber + weekPattern}）に改めた。その位置情報を運ぶ。</p>
 */
@Getter
public class TimetableSlotNoteUpdatedEvent extends BaseEvent {

    private final Long slotId;
    private final Long timetableId;
    private final Long teamId;
    private final String subjectName;
    /** 更新後のメモ本文（NULL/空文字なら通知抑制対象）。 */
    private final String notes;
    /** コマの曜日（デバウンスキーの一部）。 */
    private final String dayOfWeek;
    /** コマの時限（デバウンスキーの一部）。 */
    private final Integer periodNumber;
    /** コマの週パターン（デバウンスキーの一部）。 */
    private final WeekPattern weekPattern;

    public TimetableSlotNoteUpdatedEvent(Long slotId, Long timetableId, Long teamId,
                                         String subjectName, String notes,
                                         String dayOfWeek, Integer periodNumber, WeekPattern weekPattern) {
        super();
        this.slotId = slotId;
        this.timetableId = timetableId;
        this.teamId = teamId;
        this.subjectName = subjectName;
        this.notes = notes;
        this.dayOfWeek = dayOfWeek;
        this.periodNumber = periodNumber;
        this.weekPattern = weekPattern;
    }
}
