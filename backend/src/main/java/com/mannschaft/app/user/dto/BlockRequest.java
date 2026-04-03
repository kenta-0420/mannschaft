package com.mannschaft.app.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ユーザーブロックリクエストDTO。
 */
@Getter
@NoArgsConstructor
public class BlockRequest {

    /** ブロックするユーザーID */
    @NotNull
    @JsonProperty("blocked_id")
    private Long blockedId;
}
