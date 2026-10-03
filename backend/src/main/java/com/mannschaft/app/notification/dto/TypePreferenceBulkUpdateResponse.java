package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 通知種別設定一括更新レスポンスDTO（F04.3）。
 *
 * <p>{@code ignoredLockedCount} は URGENT 種別を含むことでスキップした件数。</p>
 */
@Builder(toBuilder = true)
@Getter
public class TypePreferenceBulkUpdateResponse {

    /** 実際に更新（UPSERT）した件数。 */
    int updatedCount;

    /** URGENT（ロック）種別のためスキップした件数。 */
    int ignoredLockedCount;
}
