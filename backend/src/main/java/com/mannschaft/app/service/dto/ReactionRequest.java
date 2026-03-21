package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * リアクションリクエスト。
 */
@Getter
@Setter
public class ReactionRequest {

    @NotNull
    private String reactionType;
}
