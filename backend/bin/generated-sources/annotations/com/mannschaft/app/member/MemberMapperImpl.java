package com.mannschaft.app.member;

import com.mannschaft.app.member.dto.FieldResponse;
import com.mannschaft.app.member.dto.MemberLookupResponse;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.dto.TeamPageResponse;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.entity.MemberProfileFieldEntity;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:10+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class MemberMapperImpl implements MemberMapper {

    @Override
    public TeamPageResponse toTeamPageResponse(TeamPageEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        String title = null;
        String slug = null;
        Short year = null;
        String description = null;
        String coverImageS3Key = null;
        Boolean allowSelfEdit = null;
        Integer sortOrder = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        title = entity.getTitle();
        slug = entity.getSlug();
        year = entity.getYear();
        description = entity.getDescription();
        coverImageS3Key = entity.getCoverImageS3Key();
        allowSelfEdit = entity.getAllowSelfEdit();
        sortOrder = entity.getSortOrder();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String pageType = entity.getPageType().name();
        String visibility = entity.getVisibility().name();
        String status = entity.getStatus().name();
        List<SectionResponse> sections = null;
        List<MemberProfileResponse> members = null;

        TeamPageResponse teamPageResponse = new TeamPageResponse( id, teamId, organizationId, title, slug, pageType, year, description, coverImageS3Key, visibility, status, allowSelfEdit, sortOrder, createdBy, createdAt, updatedAt, sections, members );

        return teamPageResponse;
    }

    @Override
    public TeamPageResponse toTeamPageDetailResponse(TeamPageEntity entity, List<SectionResponse> sections, List<MemberProfileResponse> members) {
        if ( entity == null && sections == null && members == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        String title = null;
        String slug = null;
        Short year = null;
        String description = null;
        String coverImageS3Key = null;
        Boolean allowSelfEdit = null;
        Integer sortOrder = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;
        if ( entity != null ) {
            id = entity.getId();
            teamId = entity.getTeamId();
            organizationId = entity.getOrganizationId();
            title = entity.getTitle();
            slug = entity.getSlug();
            year = entity.getYear();
            description = entity.getDescription();
            coverImageS3Key = entity.getCoverImageS3Key();
            allowSelfEdit = entity.getAllowSelfEdit();
            sortOrder = entity.getSortOrder();
            createdBy = entity.getCreatedBy();
            createdAt = entity.getCreatedAt();
            updatedAt = entity.getUpdatedAt();
        }
        List<SectionResponse> sections1 = null;
        List<SectionResponse> list1 = sections;
        if ( list1 != null ) {
            sections1 = new ArrayList<SectionResponse>( list1 );
        }
        List<MemberProfileResponse> members1 = null;
        List<MemberProfileResponse> list = members;
        if ( list != null ) {
            members1 = new ArrayList<MemberProfileResponse>( list );
        }

        String pageType = entity.getPageType().name();
        String visibility = entity.getVisibility().name();
        String status = entity.getStatus().name();

        TeamPageResponse teamPageResponse = new TeamPageResponse( id, teamId, organizationId, title, slug, pageType, year, description, coverImageS3Key, visibility, status, allowSelfEdit, sortOrder, createdBy, createdAt, updatedAt, sections1, members1 );

        return teamPageResponse;
    }

    @Override
    public SectionResponse toSectionResponse(TeamPageSectionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamPageId = null;
        String title = null;
        String content = null;
        String imageS3Key = null;
        String imageCaption = null;
        Integer sortOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamPageId = entity.getTeamPageId();
        title = entity.getTitle();
        content = entity.getContent();
        imageS3Key = entity.getImageS3Key();
        imageCaption = entity.getImageCaption();
        sortOrder = entity.getSortOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String sectionType = entity.getSectionType().name();

        SectionResponse sectionResponse = new SectionResponse( id, teamPageId, sectionType, title, content, imageS3Key, imageCaption, sortOrder, createdAt, updatedAt );

        return sectionResponse;
    }

    @Override
    public List<SectionResponse> toSectionResponseList(List<TeamPageSectionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SectionResponse> list = new ArrayList<SectionResponse>( entities.size() );
        for ( TeamPageSectionEntity teamPageSectionEntity : entities ) {
            list.add( toSectionResponse( teamPageSectionEntity ) );
        }

        return list;
    }

    @Override
    public MemberProfileResponse toMemberProfileResponse(MemberProfileEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamPageId = null;
        Long userId = null;
        String displayName = null;
        String memberNumber = null;
        String photoS3Key = null;
        String bio = null;
        String position = null;
        String customFieldValues = null;
        Integer sortOrder = null;
        Boolean isVisible = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamPageId = entity.getTeamPageId();
        userId = entity.getUserId();
        displayName = entity.getDisplayName();
        memberNumber = entity.getMemberNumber();
        photoS3Key = entity.getPhotoS3Key();
        bio = entity.getBio();
        position = entity.getPosition();
        customFieldValues = entity.getCustomFieldValues();
        sortOrder = entity.getSortOrder();
        isVisible = entity.getIsVisible();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        MemberProfileResponse memberProfileResponse = new MemberProfileResponse( id, teamPageId, userId, displayName, memberNumber, photoS3Key, bio, position, customFieldValues, sortOrder, isVisible, createdAt, updatedAt );

        return memberProfileResponse;
    }

    @Override
    public List<MemberProfileResponse> toMemberProfileResponseList(List<MemberProfileEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MemberProfileResponse> list = new ArrayList<MemberProfileResponse>( entities.size() );
        for ( MemberProfileEntity memberProfileEntity : entities ) {
            list.add( toMemberProfileResponse( memberProfileEntity ) );
        }

        return list;
    }

    @Override
    public MemberLookupResponse toMemberLookupResponse(MemberProfileEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long memberProfileId = null;
        Long userId = null;
        String displayName = null;
        String memberNumber = null;
        String position = null;
        String photoS3Key = null;

        memberProfileId = entity.getId();
        userId = entity.getUserId();
        displayName = entity.getDisplayName();
        memberNumber = entity.getMemberNumber();
        position = entity.getPosition();
        photoS3Key = entity.getPhotoS3Key();

        MemberLookupResponse memberLookupResponse = new MemberLookupResponse( memberProfileId, userId, displayName, memberNumber, position, photoS3Key );

        return memberLookupResponse;
    }

    @Override
    public List<MemberLookupResponse> toMemberLookupResponseList(List<MemberProfileEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MemberLookupResponse> list = new ArrayList<MemberLookupResponse>( entities.size() );
        for ( MemberProfileEntity memberProfileEntity : entities ) {
            list.add( toMemberLookupResponse( memberProfileEntity ) );
        }

        return list;
    }

    @Override
    public FieldResponse toFieldResponse(MemberProfileFieldEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        String fieldName = null;
        String options = null;
        Boolean isRequired = null;
        Integer sortOrder = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        fieldName = entity.getFieldName();
        options = entity.getOptions();
        isRequired = entity.getIsRequired();
        sortOrder = entity.getSortOrder();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String fieldType = entity.getFieldType().name();

        FieldResponse fieldResponse = new FieldResponse( id, teamId, organizationId, fieldName, fieldType, options, isRequired, sortOrder, isActive, createdAt, updatedAt );

        return fieldResponse;
    }

    @Override
    public List<FieldResponse> toFieldResponseList(List<MemberProfileFieldEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FieldResponse> list = new ArrayList<FieldResponse>( entities.size() );
        for ( MemberProfileFieldEntity memberProfileFieldEntity : entities ) {
            list.add( toFieldResponse( memberProfileFieldEntity ) );
        }

        return list;
    }
}
