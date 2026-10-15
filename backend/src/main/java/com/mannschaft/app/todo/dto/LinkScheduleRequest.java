package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スケジュール連携リクエストDTO。既存のスケジュールをTODOに紐付ける。
 */
@Getter
@RequiredArgsConstructor
public class LinkScheduleRequest {

    /** 連携するスケジュールID（必須）。 */
    @NotNull
    private final Long scheduleId;

    /**
     * 親TODO ID（nullable）。指定した場合、このTODOを親の子として配置する。
     */
    private final Long parentId;
}
