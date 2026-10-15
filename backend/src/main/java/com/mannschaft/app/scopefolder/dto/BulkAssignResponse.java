package com.mannschaft.app.scopefolder.dto;

import java.util.List;

/**
 * フォルダへの一括振り分けレスポンスDTO。
 *
 * <p>個別 scope_id の存在判定結果は漏洩防止のため返さず、件数のみを返す
 * （設計書 F15.3 §9.2）。</p>
 *
 * @param assignedCount 正常に割り当てられた件数
 * @param skippedCount  スキップされた件数（既に同フォルダに存在等）
 * @param errors        フォルダ単位のエラー（個別 scope_id の詳細は含めない）
 */
public record BulkAssignResponse(
        int assignedCount,
        int skippedCount,
        List<String> errors
) {
}
