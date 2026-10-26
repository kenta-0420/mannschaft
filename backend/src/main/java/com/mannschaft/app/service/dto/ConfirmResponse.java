package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 確定レスポンス。
 */
@Getter
@Builder
public class ConfirmResponse {

    private Long id;
    private String status;
    private LocalDateTime confirmedAt;
}
