package com.mannschaft.app.gdpr.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 退会時削除データプレビューレスポンスDTO。
 * 削除・匿名化されるデータのサマリーと警告メッセージを返す。
 */
@Getter
@Builder
public class DeletionPreviewResponse {

    /** データ保持日数（退会後30日間は論理削除状態で保持） */
    private final int retentionDays;

    /** カテゴリ別データ件数（カテゴリキー → 件数） */
    private final Map<String, Long> dataSummary;

    /** 匿名化されるデータの説明一覧 */
    private final List<AnonymizedItem> anonymized;

    /** 退会時の警告メッセージ一覧 */
    private final List<String> warnings;

    /**
     * 匿名化されるデータの説明アイテム。
     */
    @Getter
    @Builder
    public static class AnonymizedItem {

        /** カテゴリ名 */
        private final String category;

        /** 件数 */
        private final Long count;

        /** 匿名化の説明 */
        private final String note;
    }
}
