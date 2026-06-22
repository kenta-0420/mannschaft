package com.mannschaft.app.seal;

import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealScopeDefaultEntity;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 電子印鑑機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface SealMapper {

    @Mapping(target = "variant", expression = "java(entity.getVariant().name())")
    SealResponse toSealResponse(ElectronicSealEntity entity);

    List<SealResponse> toSealResponseList(List<ElectronicSealEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "scopeName", ignore = true)
    @Mapping(target = "variant", ignore = true)
    ScopeDefaultResponse toScopeDefaultResponse(SealScopeDefaultEntity entity);

    /**
     * scopeName と variant を解決済みの値で埋めて変換する。
     * scopeName は他ドメイン（team/organization）依存のため Service 層で
     * {@code NameResolverService} を用いて一括解決し本メソッドへ渡す。
     * variant は同一 seal ドメイン内で解決した値を渡す（印鑑削除済みは null）。
     */
    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "scopeName", source = "scopeName")
    @Mapping(target = "variant", source = "variant")
    ScopeDefaultResponse toScopeDefaultResponse(SealScopeDefaultEntity entity, String scopeName, SealVariant variant);

    List<ScopeDefaultResponse> toScopeDefaultResponseList(List<SealScopeDefaultEntity> entities);

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    @Mapping(target = "variant", ignore = true)
    StampLogResponse toStampLogResponse(SealStampLogEntity entity);

    /**
     * variant を解決済みの値で埋めて変換する。
     * variant は同一 seal ドメイン内で sealId→ElectronicSealEntity.variant で解決し渡す
     * （印鑑削除済みの場合は null）。
     */
    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    @Mapping(target = "variant", source = "variant")
    StampLogResponse toStampLogResponse(SealStampLogEntity entity, SealVariant variant);

    List<StampLogResponse> toStampLogResponseList(List<SealStampLogEntity> entities);
}
