package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモに TODO を紐付けるリクエスト。
 *
 * <p>指定された TODO は自分の PERSONAL スコープである必要がある。
 * スコープ違反・他ユーザー所有の場合は 404 を返す（IDOR 対策）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LinkTodoRequest {

    @NotNull
    @JsonProperty("todo_id")
    private Long todoId;
}
