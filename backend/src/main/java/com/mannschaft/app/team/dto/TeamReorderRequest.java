package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 並び替えリクエスト DTO（チーム用）。
 * PUT /teams/{id}/officers/reorder
 * PUT /teams/{id}/custom-fields/reorder
 */
@Getter
@Setter
@NoArgsConstructor
public class TeamReorderRequest {

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
