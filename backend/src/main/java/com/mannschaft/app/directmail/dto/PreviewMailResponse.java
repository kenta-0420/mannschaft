package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メールプレビューレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PreviewMailResponse {

    private final String bodyHtml;
}
