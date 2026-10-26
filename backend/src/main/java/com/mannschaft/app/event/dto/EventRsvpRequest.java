package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * RSVP送信リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventRsvpRequest {

    @NotBlank
    @Pattern(regexp = "ATTENDING|NOT_ATTENDING|MAYBE|UNDECIDED",
             message = "responseはATTENDING, NOT_ATTENDING, MAYBE, UNDECIDEDのいずれかを指定してください")
    private final String response;

    @Size(max = 500)
    private final String comment;
}
