package com.mannschaft.app.seal;

import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealScopeDefaultEntity;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:10+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SealMapperImpl implements SealMapper {

    @Override
    public SealResponse toSealResponse(ElectronicSealEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String displayText = null;
        String svgData = null;
        String sealHash = null;
        Integer generationVersion = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        displayText = entity.getDisplayText();
        svgData = entity.getSvgData();
        sealHash = entity.getSealHash();
        generationVersion = entity.getGenerationVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String variant = entity.getVariant().name();

        SealResponse sealResponse = new SealResponse( id, userId, variant, displayText, svgData, sealHash, generationVersion, createdAt, updatedAt );

        return sealResponse;
    }

    @Override
    public List<SealResponse> toSealResponseList(List<ElectronicSealEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SealResponse> list = new ArrayList<SealResponse>( entities.size() );
        for ( ElectronicSealEntity electronicSealEntity : entities ) {
            list.add( toSealResponse( electronicSealEntity ) );
        }

        return list;
    }

    @Override
    public ScopeDefaultResponse toScopeDefaultResponse(SealScopeDefaultEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long scopeId = null;
        Long sealId = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        scopeId = entity.getScopeId();
        sealId = entity.getSealId();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();

        ScopeDefaultResponse scopeDefaultResponse = new ScopeDefaultResponse( id, userId, scopeType, scopeId, sealId, createdAt, updatedAt );

        return scopeDefaultResponse;
    }

    @Override
    public List<ScopeDefaultResponse> toScopeDefaultResponseList(List<SealScopeDefaultEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ScopeDefaultResponse> list = new ArrayList<ScopeDefaultResponse>( entities.size() );
        for ( SealScopeDefaultEntity sealScopeDefaultEntity : entities ) {
            list.add( toScopeDefaultResponse( sealScopeDefaultEntity ) );
        }

        return list;
    }

    @Override
    public StampLogResponse toStampLogResponse(SealStampLogEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long sealId = null;
        String sealHashAtStamp = null;
        Long targetId = null;
        String stampDocumentHash = null;
        Boolean isRevoked = null;
        LocalDateTime revokedAt = null;
        LocalDateTime stampedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        sealId = entity.getSealId();
        sealHashAtStamp = entity.getSealHashAtStamp();
        targetId = entity.getTargetId();
        stampDocumentHash = entity.getStampDocumentHash();
        isRevoked = entity.getIsRevoked();
        revokedAt = entity.getRevokedAt();
        stampedAt = entity.getStampedAt();
        createdAt = entity.getCreatedAt();

        String targetType = entity.getTargetType().name();

        StampLogResponse stampLogResponse = new StampLogResponse( id, userId, sealId, sealHashAtStamp, targetType, targetId, stampDocumentHash, isRevoked, revokedAt, stampedAt, createdAt );

        return stampLogResponse;
    }

    @Override
    public List<StampLogResponse> toStampLogResponseList(List<SealStampLogEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<StampLogResponse> list = new ArrayList<StampLogResponse>( entities.size() );
        for ( SealStampLogEntity sealStampLogEntity : entities ) {
            list.add( toStampLogResponse( sealStampLogEntity ) );
        }

        return list;
    }
}
