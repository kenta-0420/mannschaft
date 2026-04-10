package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * TODO部分更新リクエストDTO。
 * PATCH用: nullフィールドは「変更なし」を意味する。
 */
@Getter
@RequiredArgsConstructor
public class PatchTodoRequest {

    private final LocalDate dueDate;
}
