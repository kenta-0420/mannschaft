package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 自動割当提案確定リクエストDTO。
 *
 * @param runId           確定する実行ログID
 * @param assignmentIds   確定する割当IDリスト（最低1件必須）
 * @param scheduleVersion 楽観ロック用スケジュールバージョン
 */
public record ConfirmAutoAssignRequest(
        @NotNull Long runId,
        @NotNull @Size(min = 1) List<Long> assignmentIds,
        @NotNull Integer scheduleVersion
) {}
