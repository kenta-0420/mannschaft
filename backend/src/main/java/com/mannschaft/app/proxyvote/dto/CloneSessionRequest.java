package com.mannschaft.app.proxyvote.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * セッション複製リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CloneSessionRequest {

    @Size(max = 200)
    private final String title;

    private final LocalDate meetingDate;
}
