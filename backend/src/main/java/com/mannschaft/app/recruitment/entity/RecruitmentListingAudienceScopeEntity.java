package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest.RecruitmentAudienceScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 個人札の選択公開先を、作成時点のスナップショットとして保持する。 */
@Entity
@Table(name = "recruitment_listing_audience_scopes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RecruitmentListingAudienceScopeEntity extends UuidV7Entity {

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private RecruitmentAudienceScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    public static RecruitmentListingAudienceScopeEntity of(
            Long listingId, RecruitmentAudienceScopeType scopeType, Long scopeId) {
        if (listingId == null || scopeType == null || scopeId == null) {
            throw new IllegalArgumentException("listingId, scopeType, scopeId は必須です");
        }
        return RecruitmentListingAudienceScopeEntity.builder()
                .listingId(listingId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();
    }
}
