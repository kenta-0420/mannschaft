package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/** TODO一括ステータス変更結果。従来の data 配列とロックによりスキップした ID を返す。 */
@Getter
@RequiredArgsConstructor
public class BulkStatusChangeResponse {

    private final List<TodoStatusChangeResponse> data;
    private final List<Long> skippedLockedIds;
}
