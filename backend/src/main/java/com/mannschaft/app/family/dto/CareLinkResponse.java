package com.mannschaft.app.family.dto;

import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareRelationship;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * ケアリンクレスポンスDTO。F03.12。
 */
@Getter
@Builder
public class CareLinkResponse {
    private Long id;
    private Long careRecipientUserId;
    private String careRecipientDisplayName;
    private Long watcherUserId;
    private String watcherDisplayName;
    private CareCategory careCategory;
    private CareRelationship relationship;
    private Boolean isPrimary;
    private CareLinkStatus status;
    private CareLinkInvitedBy invitedBy;
    private LocalDateTime confirmedAt;
    private Boolean notifyOnRsvp;
    private Boolean notifyOnCheckin;
    private Boolean notifyOnCheckout;
    private Boolean notifyOnAbsentAlert;
    private Boolean notifyOnDismissal;
    private LocalDateTime createdAt;
}
