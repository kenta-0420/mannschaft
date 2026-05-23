package com.mannschaft.app.todo.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.LocalDate;

/**
 * TODO部分更新リクエストDTO。
 * PATCH用: nullフィールドは「変更なし」を意味する。
 */
@Getter
public class PatchTodoRequest {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate dueDate;

    @JsonCreator
    public PatchTodoRequest(
            @JsonProperty("dueDate") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate dueDate) {
        this.dueDate = dueDate;
    }
}
