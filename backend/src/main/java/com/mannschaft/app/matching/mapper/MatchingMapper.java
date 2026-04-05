package com.mannschaft.app.matching.mapper;

import com.mannschaft.app.matching.dto.CityResponse;
import com.mannschaft.app.matching.dto.NgTeamResponse;
import com.mannschaft.app.matching.dto.NotificationPreferenceResponse;
import com.mannschaft.app.matching.dto.PrefectureResponse;
import com.mannschaft.app.matching.dto.ProposedDateResponse;
import com.mannschaft.app.matching.dto.ReviewResponse;
import com.mannschaft.app.matching.dto.TemplateResponse;
import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.entity.MatchNotificationPreferenceEntity;
import com.mannschaft.app.matching.entity.MatchProposalDateEntity;
import com.mannschaft.app.matching.entity.MatchRequestTemplateEntity;
import com.mannschaft.app.matching.entity.MatchReviewEntity;
import com.mannschaft.app.matching.entity.NgTeamEntity;
import com.mannschaft.app.matching.entity.PrefectureEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * マッチング機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface MatchingMapper {

    PrefectureResponse toPrefectureResponse(PrefectureEntity entity);

    List<PrefectureResponse> toPrefectureResponseList(List<PrefectureEntity> entities);

    CityResponse toCityResponse(CityEntity entity);

    List<CityResponse> toCityResponseList(List<CityEntity> entities);

    @Mapping(target = "proposedDate", source = "proposedDate")
    ProposedDateResponse toProposedDateResponse(MatchProposalDateEntity entity);

    List<ProposedDateResponse> toProposedDateResponseList(List<MatchProposalDateEntity> entities);

    /**
     * レビューエンティティをレスポンスに変換する。is_public=false の場合はコメントをnullにする。
     */
    default ReviewResponse toReviewResponse(MatchReviewEntity entity) {
        return new ReviewResponse(
                entity.getId(),
                entity.getProposalId(),
                entity.getReviewerTeamId(),
                entity.getRating(),
                entity.getIsPublic() ? entity.getComment() : null,
                entity.getIsPublic(),
                entity.getCreatedAt()
        );
    }

    List<ReviewResponse> toReviewResponseList(List<MatchReviewEntity> entities);

    @Mapping(target = "blockedTeamId", source = "blockedTeamId")
    NgTeamResponse toNgTeamResponse(NgTeamEntity entity);

    List<NgTeamResponse> toNgTeamResponseList(List<NgTeamEntity> entities);

    TemplateResponse toTemplateResponse(MatchRequestTemplateEntity entity);

    List<TemplateResponse> toTemplateResponseList(List<MatchRequestTemplateEntity> entities);

    @Mapping(target = "activityType", expression = "java(entity.getActivityType() != null ? entity.getActivityType().name() : null)")
    @Mapping(target = "category", expression = "java(entity.getCategory() != null ? entity.getCategory().name() : null)")
    NotificationPreferenceResponse toNotificationPreferenceResponse(MatchNotificationPreferenceEntity entity);
}
