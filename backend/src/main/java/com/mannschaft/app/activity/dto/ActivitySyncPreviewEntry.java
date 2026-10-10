package com.mannschaft.app.activity.dto;

import java.util.List;

/** 確認時点の活動versionと予定由来の差分。 */
public record ActivitySyncPreviewEntry(Long id, Long version, String status,
                                       List<ActivitySyncChange> changes) {
}
