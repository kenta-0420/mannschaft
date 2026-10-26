package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 参加者削除リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RemoveParticipantsRequest {

    @NotEmpty
    private final List<Long> userIds;
}
