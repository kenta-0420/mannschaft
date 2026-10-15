package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * F08.7 シフト予算 月次締め レスポンス DTO（API #11）。
 *
 * <p>設計書 F08.7 (v1.2) §6.1 #11 / §4.6 に準拠。</p>
 *
 * <p>Phase 10-β 拡張: {@code processed_organization_ids} / {@code failed_organization_ids} /
 * {@code already_closed_organization_ids} を追加。
 * 全組織横断の {@code closeAll} 経路で失敗組織のリストを返却し、
 * 運用者が個別再実行 API を叩けるようにする。
 * 単一組織の {@code close} 経路では空配列を返す（既存挙動互換）。</p>
 */
@Builder
public record MonthlyCloseResponse(

        /** 単一組織の close 経路で指定された組織 ID（closeAll 経路では null） */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("organization_id")
        Long organizationId,

        @JsonProperty("year_month")
        String yearMonth,

        /** 今回新規に締めた allocation 件数（全組織合算） */
        @JsonProperty("closed_allocations")
        int closedAllocations,

        /** 既に締め済として skip した allocation 件数（冪等性確認用、全組織合算） */
        @JsonProperty("already_closed_allocations")
        int alreadyClosedAllocations,

        /** PLANNED → CONFIRMED に遷移した consumption レコードの総件数（全組織合算） */
        @JsonProperty("closed_consumptions")
        int closedConsumptions,

        /** Phase 10-β: 締め処理が成功した組織 ID 一覧 */
        @JsonProperty("processed_organization_ids")
        List<Long> processedOrganizationIds,

        /** Phase 10-β: 締め処理が失敗した組織 ID 一覧（運用者は個別再実行 API で対処） */
        @JsonProperty("failed_organization_ids")
        List<Long> failedOrganizationIds,

        /** Phase 10-β: 既に締め済として全 allocation を skip した組織 ID 一覧 */
        @JsonProperty("already_closed_organization_ids")
        List<Long> alreadyClosedOrganizationIds
) {
}
