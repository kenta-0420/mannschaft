package com.mannschaft.app.activity.service;

import java.util.Set;

/** 作成時の予定由来値と、値を戻しても失われない手動編集済み項目を保存する。 */
public record ActivityScheduleSyncState(ActivityScheduleValues baseline, Set<String> manualFields) {}
