package com.mannschaft.app.proxyvote.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 委任状提出リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class DelegateRequest {

    private final Long delegateId;

    private final Boolean isBlank;

    private final Long electronicSealId;

    private final String reason;
}
