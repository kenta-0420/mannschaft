package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * シフト交代リクエスト作成DTO。
 *
 * <p>受信者モード:
 * <ul>
 *   <li>SPECIFIC（デフォルト）: targetUserIds に指定されたユーザーへの交代依頼。
 *       後方互換のため targetUserIds が null でも許容し SPECIFIC として扱う。</li>
 *   <li>OPEN_CALL: isOpenCall=true を指定すると不特定多数への公開募集となる。</li>
 * </ul>
 * TEMPLATE モードはフロントエンドが解決して SPECIFIC として送信するため、
 * バックエンドは SPECIFIC と OPEN_CALL の 2 モードのみ対応する。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateSwapRequestRequest {

    @NotNull
    private Long slotId;

    @Size(max = 500)
    private String reason;

    /**
     * オープンコール（全体公開）フラグ。
     * true の場合、recipientMode を OPEN_CALL として扱う。
     */
    private boolean openCall = false;

    /**
     * 交代対象ユーザーIDリスト（SPECIFIC モード時）。
     * openCall=false の場合に使用する。null の場合は後方互換として SPECIFIC 扱い。
     */
    @Size(min = 1, message = "交代対象ユーザーIDを少なくとも1件指定してください")
    private List<Long> targetUserIds;
}
