package com.mannschaft.app.social;

import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.entity.UserSocialProfileEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * ソーシャル機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface SocialMapper {

    @Mapping(target = "followerType", expression = "java(entity.getFollowerType().name())")
    @Mapping(target = "followedType", expression = "java(entity.getFollowedType().name())")
    FollowResponse toFollowResponse(FollowEntity entity);

    List<FollowResponse> toFollowResponseList(List<FollowEntity> entities);

    /**
     * UserSocialProfileEntity → ProfileResponse への変換はフォロー数の集計が必要なため Service で行う。
     */
}
