package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 本人の共通会員証確保結果。署名秘密やQRは含めない。 */
@Getter
@RequiredArgsConstructor
public class PlatformCardResponse {
    private final Long memberCardId;
    private final String scopeType;
    private final String status;
}
