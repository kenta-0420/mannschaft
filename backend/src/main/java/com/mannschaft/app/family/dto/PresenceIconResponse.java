package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * プレゼンスカスタムアイコンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresenceIconResponse {

    private final String eventType;
    private final String icon;
}
