package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WARNING自主修正完了通知リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SelfCorrectRequest {

    @Size(max = 1000)
    private final String correctionNote;
}
