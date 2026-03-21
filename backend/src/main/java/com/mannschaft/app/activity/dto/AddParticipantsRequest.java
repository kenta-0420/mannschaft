package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 参加者追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddParticipantsRequest {

    @NotEmpty
    private final List<CreateActivityRequest.ParticipantInput> participants;
}
