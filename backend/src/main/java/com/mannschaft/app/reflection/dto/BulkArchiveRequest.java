package com.mannschaft.app.reflection.dto;

/**
 * 一括アーカイブリクエスト（F06.5 Phase 3・EP #21・§12.4）。
 *
 * <p>3フィールドすべて null は 400 で拒否（全件一括アーカイブを防ぐ安全弁）。</p>
 *
 * @param academicYear 一括対象の学年度。null なら条件に含めない
 * @param termLabel    一括対象の学期ラベル。null なら条件に含めない
 * @param subjectName  一括対象の科目名。null なら条件に含めない
 */
public record BulkArchiveRequest(
        Integer academicYear,
        String termLabel,
        String subjectName
) {
}
