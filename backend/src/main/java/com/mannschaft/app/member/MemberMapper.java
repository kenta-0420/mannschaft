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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * メンバー紹介機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface MemberMapper {

    @Mapping(target = "pageType", expression = "java(entity.getPageType().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "sections", ignore = true)
    @Mapping(target = "members", ignore = true)
    TeamPageResponse toTeamPageResponse(TeamPageEntity entity);

    @Mapping(target = "pageType", expression = "java(entity.getPageType().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    TeamPageResponse toTeamPageDetailResponse(TeamPageEntity entity,
                                              List<SectionResponse> sections,
                                              List<MemberProfileResponse> members);

    @Mapping(target = "sectionType", expression = "java(entity.getSectionType().name())")
    SectionResponse toSectionResponse(TeamPageSectionEntity entity);

    List<SectionResponse> toSectionResponseList(List<TeamPageSectionEntity> entities);

    MemberProfileResponse toMemberProfileResponse(MemberProfileEntity entity);

    List<MemberProfileResponse> toMemberProfileResponseList(List<MemberProfileEntity> entities);

    @Mapping(target = "memberProfileId", source = "id")
    MemberLookupResponse toMemberLookupResponse(MemberProfileEntity entity);

    List<MemberLookupResponse> toMemberLookupResponseList(List<MemberProfileEntity> entities);

    @Mapping(target = "fieldType", expression = "java(entity.getFieldType().name())")
    FieldResponse toFieldResponse(MemberProfileFieldEntity entity);

    List<FieldResponse> toFieldResponseList(List<MemberProfileFieldEntity> entities);
}
