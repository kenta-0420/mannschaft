package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コメントレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CommentResponse {

    private final Long id;
    private final Long todoId;
    private final ProjectResponse.UserInfo user;
    private final String body;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
