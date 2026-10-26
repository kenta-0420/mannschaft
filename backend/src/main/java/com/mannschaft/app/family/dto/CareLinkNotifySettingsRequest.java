package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ケアリンク通知設定更新リクエストDTO。F03.12。
 */
@Getter
@NoArgsConstructor
public class CareLinkNotifySettingsRequest {
    private Boolean notifyOnRsvp;
    private Boolean notifyOnCheckin;
    private Boolean notifyOnCheckout;
    private Boolean notifyOnAbsentAlert;
    private Boolean notifyOnDismissal;
}
