package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * チャンネルメンバー追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddMemberRequest {

    @NotEmpty
    private final List<Long> userIds;
}
