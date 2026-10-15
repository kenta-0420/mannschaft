package com.mannschaft.app.disclosure.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 重要事項説明書 出力履歴に対する電子印鑑承認回覧開始リクエスト DTO（F09.14 Phase 3-D）。
 *
 * <p>設計書 §4 / §5.6 に対応。出力履歴を承認のために回覧する際、ADMIN が
 * 「理事長押印」等のフローを開始する手動トリガとして使用する。</p>
 *
 * @param recipientUserIds 受信者ユーザー ID（押印対象者）。1 名以上必須
 * @param circulationMode  回覧モード（{@code SEQUENTIAL} / {@code SIMULTANEOUS}）
 * @param dueDate          回覧期限（任意）
 */
public record DisclosureCirculationStartRequest(
        @NotNull
        @NotEmpty
        @Size(min = 1, max = 100)
        List<Long> recipientUserIds,

        @NotNull
        String circulationMode,

        LocalDate dueDate
) {
}
