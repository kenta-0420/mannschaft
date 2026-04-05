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
    ScopeDefaultResponse toScopeDefaultResponse(SealScopeDefaultEntity entity);

    List<ScopeDefaultResponse> toScopeDefaultResponseList(List<SealScopeDefaultEntity> entities);

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    StampLogResponse toStampLogResponse(SealStampLogEntity entity);

    List<StampLogResponse> toStampLogResponseList(List<SealStampLogEntity> entities);
}
