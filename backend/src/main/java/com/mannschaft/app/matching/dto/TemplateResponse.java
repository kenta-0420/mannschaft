package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * テンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateResponse {

    private final Long id;
    private final String name;
    private final String templateJson;
    private final LocalDateTime createdAt;
}
