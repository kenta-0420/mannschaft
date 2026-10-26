package com.mannschaft.app.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 点呼セッションリクエストDTO。F03.12 §14 主催者点呼機能。
 *
 * <p>1回の点呼操作（セッション）で複数メンバーの出欠を一括登録する。
 * rollCallSessionId は冪等キーとして機能し、同一 ID を再送した場合は上書きとなる。</p>
 */
@Getter
@RequiredArgsConstructor
public class RollCallSessionRequest {

    /**
     * 点呼セッションID（UUID形式）。クライアントが生成する冪等キー。
     * 同一 rollCallSessionId + userId の組み合わせが既存の場合は UPDATE として扱う。
     * 必須。
     */
    @NotNull
    private final String rollCallSessionId;

    /**
     * 点呼エントリ一覧。1件以上必須。
     */
    @NotEmpty
    @Valid
    private final List<RollCallEntryRequest> entries;

    /**
     * 保護者へ即時通知するかどうか。デフォルト true。
     * false の場合、ケア対象者の PRESENT 記録でも保護者通知を送信しない。
     */
    private final boolean notifyGuardiansImmediately;

    /**
     * notifyGuardiansImmediately を省略した場合に true を適用するファクトリ。
     */
    public static RollCallSessionRequest of(String rollCallSessionId,
                                             List<RollCallEntryRequest> entries) {
        return new RollCallSessionRequest(rollCallSessionId, entries, true);
    }
}
