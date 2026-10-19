package com.mannschaft.app.membership.mapper;

import com.mannschaft.app.membership.dto.PlatformCardResponse;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 共通会員証の公開最小DTO変換。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PlatformCardMapper {
    @Mapping(source = "id", target = "memberCardId")
    PlatformCardResponse toResponse(MemberCardEntity card);
}
