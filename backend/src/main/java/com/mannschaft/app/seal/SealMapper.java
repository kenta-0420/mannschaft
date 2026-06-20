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
    ScopeDefaultResponse toScopeDefaultResponse(SealScopeDefaultEntity entity);

    /**
     * scopeName を解決済みの値で埋めて変換する。
     * scopeName は他ドメイン（team/organization）依存のため Service 層で
     * {@code NameResolverService} を用いて一括解決し、本メソッドへ渡す。
     */
    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "scopeName", source = "scopeName")
    ScopeDefaultResponse toScopeDefaultResponse(SealScopeDefaultEntity entity, String scopeName);

    List<ScopeDefaultResponse> toScopeDefaultResponseList(List<SealScopeDefaultEntity> entities);

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    StampLogResponse toStampLogResponse(SealStampLogEntity entity);

    List<StampLogResponse> toStampLogResponseList(List<SealStampLogEntity> entities);
}
