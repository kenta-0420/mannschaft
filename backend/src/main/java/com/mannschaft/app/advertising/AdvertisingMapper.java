package com.mannschaft.app.advertising;

import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.dto.AdRateCardResponse;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.AffiliateConfigResponse;
import com.mannschaft.app.advertising.dto.CreateAffiliateConfigRequest;
import com.mannschaft.app.advertising.dto.PublicRateCardResponse;
import com.mannschaft.app.advertising.entity.AdRateCardEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
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

    /**
     * 広告主アカウントエンティティからレスポンスに変換する。
     */
    AdvertiserAccountResponse toAccountResponse(AdvertiserAccountEntity entity);

    /**
     * 料金カードエンティティからレスポンスに変換する。
     */
    AdRateCardResponse toRateCardResponse(AdRateCardEntity entity);

    /**
     * 料金カードエンティティから公開レスポンスに変換する。
     */
    @Mapping(target = "label", expression = "java(buildRateCardLabel(entity))")
    PublicRateCardResponse toPublicRateCardResponse(AdRateCardEntity entity);

    /**
     * 料金カードのラベルを組み立てる。
     */
    default String buildRateCardLabel(AdRateCardEntity entity) {
        String prefecture = entity.getTargetPrefecture();
        String template = entity.getTargetTemplate();
        if (prefecture != null && template != null) {
            return prefecture + " × " + template;
        } else if (prefecture != null) {
            return prefecture;
        } else if (template != null) {
            return template;
        } else {
            return "全国共通";
        }
    }
}
