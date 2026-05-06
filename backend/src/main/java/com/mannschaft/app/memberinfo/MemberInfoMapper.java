package com.mannschaft.app.memberinfo;

import com.mannschaft.app.memberinfo.dto.MemberInfoFieldResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MemberInfoMapper {
    MemberInfoFieldResponse toFieldResponse(TeamMemberInfoFieldEntity entity);
    List<MemberInfoFieldResponse> toFieldResponseList(List<TeamMemberInfoFieldEntity> entities);
}
