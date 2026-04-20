package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteePurposeTag;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 委員会詳細レスポンス DTO。
 */
@Getter
@Builder(toBuilder = true)
public class CommitteeResponse {

    private Long id;
    private Long organizationId;
    private String name;
    private String description;
    private CommitteePurposeTag purposeTag;
    private LocalDate startDate;
    private LocalDate endDate;
    private CommitteeStatus status;
    private CommitteeVisibility visibilityToOrg;
    private ConfirmationMode defaultConfirmationMode;
    private boolean defaultAnnouncementEnabled;
    private DistributionScope defaultDistributionScope;
    private LocalDateTime archivedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** ログインユーザーのロール（非メンバーの場合は null） */
    private CommitteeRole myRole;

    /**
     * CommitteeEntity から CommitteeResponse を生成する。
     * myRole は後から {@link CommitteeResponseBuilder#myRole(CommitteeRole)} でセットする。
     */
    public static CommitteeResponse of(CommitteeEntity entity) {
        return CommitteeResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .name(entity.getName())
                .description(entity.getDescription())
                .purposeTag(entity.getPurposeTag())
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .status(entity.getStatus())
                .visibilityToOrg(entity.getVisibilityToOrg())
                .defaultConfirmationMode(entity.getDefaultConfirmationMode())
                .defaultAnnouncementEnabled(entity.isDefaultAnnouncementEnabled())
                .defaultDistributionScope(entity.getDefaultDistributionScope())
                .archivedAt(entity.getArchivedAt())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
