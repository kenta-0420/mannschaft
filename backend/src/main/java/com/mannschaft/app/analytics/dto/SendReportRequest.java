package com.mannschaft.app.analytics.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 月次レポート手動送信リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class SendReportRequest {

    @NotEmpty
    @Size(max = 10)
    private final List<@Email String> recipients;
}
