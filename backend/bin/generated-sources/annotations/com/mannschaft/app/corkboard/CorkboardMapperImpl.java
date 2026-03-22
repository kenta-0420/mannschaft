package com.mannschaft.app.corkboard;

import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
import com.mannschaft.app.corkboard.dto.CorkboardResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class CorkboardMapperImpl implements CorkboardMapper {

    @Override
    public CorkboardResponse toBoardResponse(CorkboardEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        Long ownerId = null;
        String name = null;
        String backgroundStyle = null;
        String editPolicy = null;
        Boolean isDefault = null;
        Long version = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        ownerId = entity.getOwnerId();
        name = entity.getName();
        backgroundStyle = entity.getBackgroundStyle();
        editPolicy = entity.getEditPolicy();
        isDefault = entity.getIsDefault();
        version = entity.getVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        CorkboardResponse corkboardResponse = new CorkboardResponse( id, scopeType, scopeId, ownerId, name, backgroundStyle, editPolicy, isDefault, version, createdAt, updatedAt );

        return corkboardResponse;
    }

    @Override
    public List<CorkboardResponse> toBoardResponseList(List<CorkboardEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CorkboardResponse> list = new ArrayList<CorkboardResponse>( entities.size() );
        for ( CorkboardEntity corkboardEntity : entities ) {
            list.add( toBoardResponse( corkboardEntity ) );
        }

        return list;
    }

    @Override
    public CorkboardCardResponse toCardResponse(CorkboardCardEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long corkboardId = null;
        String cardType = null;
        String referenceType = null;
        Long referenceId = null;
        String contentSnapshot = null;
        String title = null;
        String body = null;
        String url = null;
        String ogTitle = null;
        String ogImageUrl = null;
        String ogDescription = null;
        String colorLabel = null;
        String cardSize = null;
        Integer positionX = null;
        Integer positionY = null;
        String userNote = null;
        LocalDateTime autoArchiveAt = null;
        Boolean isArchived = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        corkboardId = entity.getCorkboardId();
        cardType = entity.getCardType();
        referenceType = entity.getReferenceType();
        referenceId = entity.getReferenceId();
        contentSnapshot = entity.getContentSnapshot();
        title = entity.getTitle();
        body = entity.getBody();
        url = entity.getUrl();
        ogTitle = entity.getOgTitle();
        ogImageUrl = entity.getOgImageUrl();
        ogDescription = entity.getOgDescription();
        colorLabel = entity.getColorLabel();
        cardSize = entity.getCardSize();
        positionX = entity.getPositionX();
        positionY = entity.getPositionY();
        userNote = entity.getUserNote();
        autoArchiveAt = entity.getAutoArchiveAt();
        isArchived = entity.getIsArchived();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        Integer zIndex = entity.getZIndex();

        CorkboardCardResponse corkboardCardResponse = new CorkboardCardResponse( id, corkboardId, cardType, referenceType, referenceId, contentSnapshot, title, body, url, ogTitle, ogImageUrl, ogDescription, colorLabel, cardSize, positionX, positionY, zIndex, userNote, autoArchiveAt, isArchived, createdBy, createdAt, updatedAt );

        return corkboardCardResponse;
    }

    @Override
    public List<CorkboardCardResponse> toCardResponseList(List<CorkboardCardEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CorkboardCardResponse> list = new ArrayList<CorkboardCardResponse>( entities.size() );
        for ( CorkboardCardEntity corkboardCardEntity : entities ) {
            list.add( toCardResponse( corkboardCardEntity ) );
        }

        return list;
    }

    @Override
    public CorkboardGroupResponse toGroupResponse(CorkboardGroupEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long corkboardId = null;
        String name = null;
        Boolean isCollapsed = null;
        Integer positionX = null;
        Integer positionY = null;
        Integer width = null;
        Integer height = null;
        Short displayOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        corkboardId = entity.getCorkboardId();
        name = entity.getName();
        isCollapsed = entity.getIsCollapsed();
        positionX = entity.getPositionX();
        positionY = entity.getPositionY();
        width = entity.getWidth();
        height = entity.getHeight();
        displayOrder = entity.getDisplayOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        CorkboardGroupResponse corkboardGroupResponse = new CorkboardGroupResponse( id, corkboardId, name, isCollapsed, positionX, positionY, width, height, displayOrder, createdAt, updatedAt );

        return corkboardGroupResponse;
    }

    @Override
    public List<CorkboardGroupResponse> toGroupResponseList(List<CorkboardGroupEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CorkboardGroupResponse> list = new ArrayList<CorkboardGroupResponse>( entities.size() );
        for ( CorkboardGroupEntity corkboardGroupEntity : entities ) {
            list.add( toGroupResponse( corkboardGroupEntity ) );
        }

        return list;
    }
}
