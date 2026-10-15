package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 担当者レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AssigneeResponse {

    private final Long id;
    private final Long userId;
    private final String displayName;
    private final Long assignedBy;
    private final LocalDateTime createdAt;
}
