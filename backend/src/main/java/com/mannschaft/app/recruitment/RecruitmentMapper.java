package com.mannschaft.app.recruitment;

import com.mannschaft.app.recruitment.dto.CancellationPolicyResponse;
import com.mannschaft.app.recruitment.dto.CancellationPolicyTierResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentCategoryResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentFeedItemResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentSubcategoryResponse;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyTierEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentSubcategoryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * F03.11 募集型予約の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface RecruitmentMapper {

    @Mapping(target = "defaultParticipationType", expression = "java(entity.getDefaultParticipationType().name())")
    RecruitmentCategoryResponse toCategoryResponse(RecruitmentCategoryEntity entity);

    List<RecruitmentCategoryResponse> toCategoryResponseList(List<RecruitmentCategoryEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    RecruitmentSubcategoryResponse toSubcategoryResponse(RecruitmentSubcategoryEntity entity);

    List<RecruitmentSubcategoryResponse> toSubcategoryResponseList(List<RecruitmentSubcategoryEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "participationType", expression = "java(entity.getParticipationType().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "categoryNameI18nKey", ignore = true)
    @Mapping(target = "subcategoryName", ignore = true)
    RecruitmentListingResponse toListingResponse(RecruitmentListingEntity entity);

    @Mapping(target = "participationType", expression = "java(entity.getParticipationType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "categoryNameI18nKey", ignore = true)
    RecruitmentListingSummaryResponse toListingSummaryResponse(RecruitmentListingEntity entity);

    List<RecruitmentListingSummaryResponse> toListingSummaryResponseList(List<RecruitmentListingEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "participationType", expression = "java(entity.getParticipationType().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "categoryNameI18nKey", ignore = true)
    RecruitmentFeedItemResponse toFeedItemResponse(RecruitmentListingEntity entity);

    List<RecruitmentFeedItemResponse> toFeedItemResponseList(List<RecruitmentListingEntity> entities);

    @Mapping(target = "participantType", expression = "java(entity.getParticipantType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    RecruitmentParticipantResponse toParticipantResponse(RecruitmentParticipantEntity entity);

    List<RecruitmentParticipantResponse> toParticipantResponseList(List<RecruitmentParticipantEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "tiers", ignore = true)
    CancellationPolicyResponse toCancellationPolicyResponse(RecruitmentCancellationPolicyEntity entity);

    @Mapping(target = "feeType", expression = "java(entity.getFeeType().name())")
    CancellationPolicyTierResponse toCancellationPolicyTierResponse(RecruitmentCancellationPolicyTierEntity entity);

    List<CancellationPolicyTierResponse> toCancellationPolicyTierResponseList(List<RecruitmentCancellationPolicyTierEntity> entities);
}
