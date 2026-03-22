package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 配信対象数見積レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EstimateRecipientsResponse {

    private final Integer estimatedRecipients;
}
