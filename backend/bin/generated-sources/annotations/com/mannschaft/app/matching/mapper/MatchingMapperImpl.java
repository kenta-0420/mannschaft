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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:09+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class MatchingMapperImpl implements MatchingMapper {

    @Override
    public PrefectureResponse toPrefectureResponse(PrefectureEntity entity) {
        if ( entity == null ) {
            return null;
        }

        String code = null;
        String name = null;

        code = entity.getCode();
        name = entity.getName();

        PrefectureResponse prefectureResponse = new PrefectureResponse( code, name );

        return prefectureResponse;
    }

    @Override
    public List<PrefectureResponse> toPrefectureResponseList(List<PrefectureEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PrefectureResponse> list = new ArrayList<PrefectureResponse>( entities.size() );
        for ( PrefectureEntity prefectureEntity : entities ) {
            list.add( toPrefectureResponse( prefectureEntity ) );
        }

        return list;
    }

    @Override
    public CityResponse toCityResponse(CityEntity entity) {
        if ( entity == null ) {
            return null;
        }

        String code = null;
        String name = null;

        code = entity.getCode();
        name = entity.getName();

        CityResponse cityResponse = new CityResponse( code, name );

        return cityResponse;
    }

    @Override
    public List<CityResponse> toCityResponseList(List<CityEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CityResponse> list = new ArrayList<CityResponse>( entities.size() );
        for ( CityEntity cityEntity : entities ) {
            list.add( toCityResponse( cityEntity ) );
        }

        return list;
    }

    @Override
    public ProposedDateResponse toProposedDateResponse(MatchProposalDateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        LocalDate proposedDate = null;
        Long id = null;
        LocalTime proposedTimeFrom = null;
        LocalTime proposedTimeTo = null;
        Boolean isSelected = null;

        proposedDate = entity.getProposedDate();
        id = entity.getId();
        proposedTimeFrom = entity.getProposedTimeFrom();
        proposedTimeTo = entity.getProposedTimeTo();
        isSelected = entity.getIsSelected();

        ProposedDateResponse proposedDateResponse = new ProposedDateResponse( id, proposedDate, proposedTimeFrom, proposedTimeTo, isSelected );

        return proposedDateResponse;
    }

    @Override
    public List<ProposedDateResponse> toProposedDateResponseList(List<MatchProposalDateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ProposedDateResponse> list = new ArrayList<ProposedDateResponse>( entities.size() );
        for ( MatchProposalDateEntity matchProposalDateEntity : entities ) {
            list.add( toProposedDateResponse( matchProposalDateEntity ) );
        }

        return list;
    }

    @Override
    public List<ReviewResponse> toReviewResponseList(List<MatchReviewEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReviewResponse> list = new ArrayList<ReviewResponse>( entities.size() );
        for ( MatchReviewEntity matchReviewEntity : entities ) {
            list.add( toReviewResponse( matchReviewEntity ) );
        }

        return list;
    }

    @Override
    public NgTeamResponse toNgTeamResponse(NgTeamEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long blockedTeamId = null;
        LocalDateTime createdAt = null;

        blockedTeamId = entity.getBlockedTeamId();
        createdAt = entity.getCreatedAt();

        NgTeamResponse ngTeamResponse = new NgTeamResponse( blockedTeamId, createdAt );

        return ngTeamResponse;
    }

    @Override
    public List<NgTeamResponse> toNgTeamResponseList(List<NgTeamEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<NgTeamResponse> list = new ArrayList<NgTeamResponse>( entities.size() );
        for ( NgTeamEntity ngTeamEntity : entities ) {
            list.add( toNgTeamResponse( ngTeamEntity ) );
        }

        return list;
    }

    @Override
    public TemplateResponse toTemplateResponse(MatchRequestTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String templateJson = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        name = entity.getName();
        templateJson = entity.getTemplateJson();
        createdAt = entity.getCreatedAt();

        TemplateResponse templateResponse = new TemplateResponse( id, name, templateJson, createdAt );

        return templateResponse;
    }

    @Override
    public List<TemplateResponse> toTemplateResponseList(List<MatchRequestTemplateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TemplateResponse> list = new ArrayList<TemplateResponse>( entities.size() );
        for ( MatchRequestTemplateEntity matchRequestTemplateEntity : entities ) {
            list.add( toTemplateResponse( matchRequestTemplateEntity ) );
        }

        return list;
    }

    @Override
    public NotificationPreferenceResponse toNotificationPreferenceResponse(MatchNotificationPreferenceEntity entity) {
        if ( entity == null ) {
            return null;
        }

        String prefectureCode = null;
        String cityCode = null;
        Boolean isEnabled = null;

        prefectureCode = entity.getPrefectureCode();
        cityCode = entity.getCityCode();
        isEnabled = entity.getIsEnabled();

        String activityType = entity.getActivityType() != null ? entity.getActivityType().name() : null;
        String category = entity.getCategory() != null ? entity.getCategory().name() : null;

        NotificationPreferenceResponse notificationPreferenceResponse = new NotificationPreferenceResponse( prefectureCode, cityCode, activityType, category, isEnabled );

        return notificationPreferenceResponse;
    }
}
