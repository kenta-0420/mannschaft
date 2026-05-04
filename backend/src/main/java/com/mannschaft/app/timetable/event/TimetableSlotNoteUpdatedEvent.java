package com.mannschaft.app.timetable.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * F03.15 Phase 4: チーム時間割コマの共通メモ（{@code timetable_slots.notes}）が更新された
 * ときに発行されるイベント。
 *
 * <p>購読者: {@code TeamSlotNoteNotifyListener}。デバウンス（5分）後に
 * チームメンバー個別の {@code personal_timetable_settings.notify_team_slot_note_updates}
 * を確認のうえプッシュ通知する。</p>
 */
@Getter
public class TimetableSlotNoteUpdatedEvent extends BaseEvent {

    private final Long slotId;
    private final Long timetableId;
    private final Long teamId;
    private final String subjectName;
    /** 更新後のメモ本文（NULL/空文字なら通知抑制対象）。 */
    private final String notes;

    public TimetableSlotNoteUpdatedEvent(Long slotId, Long timetableId, Long teamId,
                                         String subjectName, String notes) {
        super();
        this.slotId = slotId;
        this.timetableId = timetableId;
        this.teamId = teamId;
        this.subjectName = subjectName;
        this.notes = notes;
    }
}
