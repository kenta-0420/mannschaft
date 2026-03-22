package com.mannschaft.app.advertising;

import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.dto.AffiliateConfigResponse;
import com.mannschaft.app.advertising.dto.CreateAffiliateConfigRequest;
import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 広告機能のMapStructマッパー。
 */
@Mapper(componentModel = "spring")
public interface AdvertisingMapper {

    /**
     * エンティティからSYSTEM_ADMIN用レスポンスに変換する。
     */
    @Mapping(target = "provider", expression = "java(entity.getProvider().name())")
    @Mapping(target = "placement", expression = "java(entity.getPlacement().name())")
    AffiliateConfigResponse toResponse(AffiliateConfigEntity entity);

    /**
     * エンティティから公開API用レスポンスに変換する。
     */
    @Mapping(target = "provider", expression = "java(entity.getProvider().name())")
    @Mapping(target = "placement", expression = "java(entity.getPlacement().name())")
    ActiveAdResponse toActiveAdResponse(AffiliateConfigEntity entity);

    /**
     * 作成リクエストからエンティティに変換する。
     */
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "isActive", constant = "true")
    @Mapping(target = "provider", expression = "java(AffiliateProvider.valueOf(request.getProvider()))")
    @Mapping(target = "placement", expression = "java(AdPlacement.valueOf(request.getPlacement()))")
    AffiliateConfigEntity toEntity(CreateAffiliateConfigRequest request);
}
