package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * チームケア通知上書きリクエストDTO。F03.12。
 */
@Getter
@NoArgsConstructor
public class TeamCareOverrideRequest {
    private Boolean notifyOnRsvp;
    private Boolean notifyOnCheckin;
    private Boolean notifyOnCheckout;
    private Boolean notifyOnAbsentAlert;
    private Boolean notifyOnDismissal;
    private Boolean disabled;
}
