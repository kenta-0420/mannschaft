package com.mannschaft.app.timetable.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * F03.15 Phase 4: 臨時変更が削除されたときに発行されるイベント。
 *
 * <p>個人カレンダーへ自動反映済みのスケジュールを取り消すために使用する。
 * 購読者: {@code PersonalTimetableLinkSyncListener}。</p>
 */
@Getter
public class TimetableChangeDeletedEvent extends BaseEvent {

    private final Long changeId;
    private final Long timetableId;

    public TimetableChangeDeletedEvent(Long changeId, Long timetableId) {
        super();
        this.changeId = changeId;
        this.timetableId = timetableId;
    }
}
