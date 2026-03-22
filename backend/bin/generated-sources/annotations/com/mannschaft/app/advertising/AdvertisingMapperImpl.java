package com.mannschaft.app.advertising;

import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.dto.AffiliateConfigResponse;
import com.mannschaft.app.advertising.dto.CreateAffiliateConfigRequest;
import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import java.time.LocalDateTime;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:09+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class AdvertisingMapperImpl implements AdvertisingMapper {

    @Override
    public AffiliateConfigResponse toResponse(AffiliateConfigEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String tagId = null;
        String description = null;
        String bannerImageUrl = null;
        Short bannerWidth = null;
        Short bannerHeight = null;
        String altText = null;
        Boolean isActive = null;
        LocalDateTime activeFrom = null;
        LocalDateTime activeUntil = null;
        Short displayPriority = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        tagId = entity.getTagId();
        description = entity.getDescription();
        bannerImageUrl = entity.getBannerImageUrl();
        bannerWidth = entity.getBannerWidth();
        bannerHeight = entity.getBannerHeight();
        altText = entity.getAltText();
        isActive = entity.getIsActive();
        activeFrom = entity.getActiveFrom();
        activeUntil = entity.getActiveUntil();
        displayPriority = entity.getDisplayPriority();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String provider = entity.getProvider().name();
        String placement = entity.getPlacement().name();

        AffiliateConfigResponse affiliateConfigResponse = new AffiliateConfigResponse( id, provider, tagId, placement, description, bannerImageUrl, bannerWidth, bannerHeight, altText, isActive, activeFrom, activeUntil, displayPriority, createdAt, updatedAt );

        return affiliateConfigResponse;
    }

    @Override
    public ActiveAdResponse toActiveAdResponse(AffiliateConfigEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String tagId = null;
        String bannerImageUrl = null;
        Short bannerWidth = null;
        Short bannerHeight = null;
        String altText = null;
        Short displayPriority = null;

        id = entity.getId();
        tagId = entity.getTagId();
        bannerImageUrl = entity.getBannerImageUrl();
        bannerWidth = entity.getBannerWidth();
        bannerHeight = entity.getBannerHeight();
        altText = entity.getAltText();
        displayPriority = entity.getDisplayPriority();

        String provider = entity.getProvider().name();
        String placement = entity.getPlacement().name();

        ActiveAdResponse activeAdResponse = new ActiveAdResponse( id, provider, tagId, placement, bannerImageUrl, bannerWidth, bannerHeight, altText, displayPriority );

        return activeAdResponse;
    }

    @Override
    public AffiliateConfigEntity toEntity(CreateAffiliateConfigRequest request) {
        if ( request == null ) {
            return null;
        }

        AffiliateConfigEntity.AffiliateConfigEntityBuilder affiliateConfigEntity = AffiliateConfigEntity.builder();

        affiliateConfigEntity.activeFrom( request.getActiveFrom() );
        affiliateConfigEntity.activeUntil( request.getActiveUntil() );
        affiliateConfigEntity.altText( request.getAltText() );
        affiliateConfigEntity.bannerHeight( request.getBannerHeight() );
        affiliateConfigEntity.bannerImageUrl( request.getBannerImageUrl() );
        affiliateConfigEntity.bannerWidth( request.getBannerWidth() );
        affiliateConfigEntity.description( request.getDescription() );
        affiliateConfigEntity.displayPriority( request.getDisplayPriority() );
        affiliateConfigEntity.tagId( request.getTagId() );

        affiliateConfigEntity.isActive( true );
        affiliateConfigEntity.provider( AffiliateProvider.valueOf(request.getProvider()) );
        affiliateConfigEntity.placement( AdPlacement.valueOf(request.getPlacement()) );

        return affiliateConfigEntity.build();
    }
}
