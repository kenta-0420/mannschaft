package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 管理者メモ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AdminNoteRequest {

    @Size(max = 500)
    private final String note;
}
