package com.mannschaft.app.social;

import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.entity.FollowEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SocialMapperImpl implements SocialMapper {

    @Override
    public FollowResponse toFollowResponse(FollowEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long followerId = null;
        Long followedId = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        followerId = entity.getFollowerId();
        followedId = entity.getFollowedId();
        createdAt = entity.getCreatedAt();

        String followerType = entity.getFollowerType().name();
        String followedType = entity.getFollowedType().name();

        FollowResponse followResponse = new FollowResponse( id, followerType, followerId, followedType, followedId, createdAt );

        return followResponse;
    }

    @Override
    public List<FollowResponse> toFollowResponseList(List<FollowEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FollowResponse> list = new ArrayList<FollowResponse>( entities.size() );
        for ( FollowEntity followEntity : entities ) {
            list.add( toFollowResponse( followEntity ) );
        }

        return list;
    }
}
