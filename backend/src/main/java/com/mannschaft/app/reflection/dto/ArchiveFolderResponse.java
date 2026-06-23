package com.mannschaft.app.reflection.dto;

import lombok.Builder;

/**
 * アーカイブフォルダ集計レスポンス（F06.5 Phase 3・EP #17・§12.4）。
 *
 * <p>学年×学期×教科 GROUP BY の結果を表現する。各フィールドが null の場合は「未設定グループ」を意味する。</p>
 */
@Builder
public record ArchiveFolderResponse(
        /** 学年度（Integer型・null=未設定グループ）。 */
        Integer academicYear,
        /** 学期ラベル（null=未設定グループ）。 */
        String termLabel,
        /** 科目名（null=科目未紐づけグループ）。 */
        String subjectName,
        /** このフォルダに属するアーカイブ済みテーマ件数。 */
        int themeCount
) {
}
