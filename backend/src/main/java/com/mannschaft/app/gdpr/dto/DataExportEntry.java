package com.mannschaft.app.gdpr.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * データエクスポートエントリDTO。
 * ZIPアーカイブ内の各カテゴリファイルの情報を表す。
 */
@Getter
@Builder
public class DataExportEntry {

    /** カテゴリ名（例: account, charts, payments） */
    private final String category;

    /** ZIPアーカイブ内のファイル名（例: account.json） */
    private final String filename;

    /** エクスポートされたレコード件数 */
    private final Long recordCount;
}
