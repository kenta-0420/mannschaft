package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コンテンツ通報作成リクエストDTO。
 *
 * <p>通報の宛先スコープ・対象ユーザー・控え（scope_type / scope_id / target_user_id /
 * content_snapshot）は BE が対象コンテンツから導出するため、本 DTO には持たない
 * （CMP-260917-1135・設計書 F10.1 §content_reports）。旧クライアントが送ってきても
 * Jackson の既定（未知項目は無視）で捨てられる。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateReportRequest {

    @NotBlank
    private final String targetType;

    @NotNull
    private final Long targetId;

    @NotBlank
    private final String reason;

    @Size(max = 1000)
    private final String description;
}
