package com.mannschaft.app.queue.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 管理者チケット操作リクエストDTO。状態遷移やメモ追加に使用する。
 */
@Getter
@RequiredArgsConstructor
public class AdminTicketRequest {

    private final String action;

    private final Short actualServiceMinutes;

    @Size(max = 300)
    private final String note;

    private final Short holdExtensionMinutes;
}
