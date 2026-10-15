package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * メンバー並び替えリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReorderRequest {

    @NotNull
    private final Long teamPageId;

    @NotNull
    private final List<OrderItem> orders;

    @Getter
    @RequiredArgsConstructor
    public static class OrderItem {
        private final Long id;
        private final Integer sortOrder;
    }
}
