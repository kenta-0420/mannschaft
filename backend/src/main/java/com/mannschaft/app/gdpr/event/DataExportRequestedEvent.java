package com.mannschaft.app.gdpr.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.util.Set;

/**
 * データエクスポート要求イベント。PersonalDataCollectorによるデータ収集をトリガーする。
 */
@Getter
public class DataExportRequestedEvent extends BaseEvent {

    private final Long userId;
    private final Long exportId;
    private final Set<String> categories;

    public DataExportRequestedEvent(Long userId, Long exportId, Set<String> categories) {
        super();
        this.userId = userId;
        this.exportId = exportId;
        this.categories = categories;
    }
}
