package com.mannschaft.app.errorreport.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * F12.5 Phase 2 — 担当者割り当てリクエスト。
 * {@code assigneeId = null} の場合は担当者解除。
 */
@Getter
@Setter
public class ErrorReportAssigneeRequest {

    private Long assigneeId;
}
