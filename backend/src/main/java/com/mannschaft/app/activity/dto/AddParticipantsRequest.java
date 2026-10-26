package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 参加者追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddParticipantsRequest {

    @NotEmpty
    private final List<Long> userIds;

    private final Map<String, String> roleLabels;
}
