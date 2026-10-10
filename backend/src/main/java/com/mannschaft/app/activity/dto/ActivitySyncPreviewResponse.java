package com.mannschaft.app.activity.dto;

import java.util.List;

/** 閲覧・編集が認可された活動記録だけを含む変更プレビュー。 */
public record ActivitySyncPreviewResponse(ExpectedScheduleState expectedScheduleState,
                                          List<ActivitySyncPreviewEntry> activities) {
}
