package com.mannschaft.app.organization.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 並び替えリクエスト DTO（組織用）。
 * PUT /organizations/{id}/officers/reorder
 * PUT /organizations/{id}/custom-fields/reorder
 */
@Getter
@Setter
@NoArgsConstructor
public class ReorderRequest {

    /** 並び替え対象の ID と表示順のリスト */
    private List<OrderItem> orders;

    /**
     * 並び替え対象の1件分データ。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class OrderItem {

        /** 対象エンティティの ID */
        private Long id;

        /** 新しい表示順 */
        private Integer displayOrder;
    }
}
