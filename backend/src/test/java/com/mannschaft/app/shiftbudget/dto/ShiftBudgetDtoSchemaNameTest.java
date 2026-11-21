package com.mannschaft.app.shiftbudget.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * springdoc は入れ子 DTO をクラスの単純名でスキーマ化するため、同名の入れ子 DTO が複数あると片方に潰れて
 * openapi.json と FE 生成型が誤る（CMP-260909-2152 PR-α 検分指摘。締め期間一覧と未計上勤務一覧が共に Item だった）。
 * 締め期間・未計上勤務の応答の入れ子 DTO は、単純名が互いに異なり、汎用名（Item）でないこと。
 */
@DisplayName("CMP-260909-2152 α 応答 DTO の入れ子スキーマ名が固有")
class ShiftBudgetDtoSchemaNameTest {

    @Test
    @DisplayName("ClosePeriodListResponse と UncountedShiftListResponse の入れ子 DTO は固有名（Item を共有しない）")
    void 入れ子DTOの名前は固有() {
        Map<String, String> owners = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (Class<?> outer : List.of(ClosePeriodListResponse.class, UncountedShiftListResponse.class)) {
            for (Class<?> nested : outer.getDeclaredClasses()) {
                String previous = owners.put(nested.getSimpleName(), outer.getSimpleName());
                if (previous != null) {
                    clashes.add(nested.getSimpleName() + " は " + previous + " と " + outer.getSimpleName() + " で重複");
                }
                assertThat(nested.getSimpleName()).as("汎用名は避ける").isNotEqualTo("Item");
            }
        }
        assertThat(clashes).isEmpty();
    }
}
