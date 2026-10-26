package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括強制完了レスポンス DTO。
 *
 * <p>Phase 11 第三陣 3-A で追加。</p>
 */
@Getter
@RequiredArgsConstructor
public class ForceCompleteBatchResponse {

    /** 成功した文書 ID リスト。 */
    private final List<Long> succeeded;

    /** 失敗した文書 ID と理由のリスト。 */
    private final List<FailureEntry> failed;

    /**
     * 一括処理失敗エントリ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class FailureEntry {
        private final Long documentId;
        private final String errorCode;
        private final String message;
    }
}
