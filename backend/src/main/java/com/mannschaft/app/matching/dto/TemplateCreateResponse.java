package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレート作成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateCreateResponse {

    private final Long id;
    private final String name;
}
