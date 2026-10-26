package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * リアクションレスポンス。
 */
@Getter
@Builder
public class ReactionResponse {

    private Long serviceRecordId;
    private String reactionType;
    private LocalDateTime createdAt;
}
