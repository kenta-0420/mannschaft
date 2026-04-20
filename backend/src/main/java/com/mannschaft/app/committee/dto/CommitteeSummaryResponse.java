package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.CommitteePurposeTag;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 委員会一覧表示用の軽量レスポンス DTO。
 */
@Getter
@Builder
public class CommitteeSummaryResponse {

    private Long id;
    private String name;
    private CommitteeStatus status;
    private CommitteeVisibility visibilityToOrg;
    private CommitteePurposeTag purposeTag;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer memberCount;
    private CommitteeRole myRole;
    private LocalDateTime createdAt;
}
