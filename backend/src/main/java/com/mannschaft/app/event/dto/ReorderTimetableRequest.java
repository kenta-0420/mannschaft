package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * タイムテーブル並び替えリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReorderTimetableRequest {

    @NotEmpty
    private final List<Long> itemIds;
}
