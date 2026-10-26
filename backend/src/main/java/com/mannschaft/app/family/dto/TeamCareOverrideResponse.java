package com.mannschaft.app.family.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * チームケア通知上書き設定レスポンスDTO。F03.12。
 */
@Getter
@Builder
public class TeamCareOverrideResponse {
    private Long id;
    private String scopeType;
    private Long scopeId;
    private Long careLinkId;
    private Boolean notifyOnRsvp;
    private Boolean notifyOnCheckin;
    private Boolean notifyOnCheckout;
    private Boolean notifyOnAbsentAlert;
    private Boolean notifyOnDismissal;
    private Boolean disabled;
}
