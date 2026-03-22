package com.mannschaft.app.directmail;

import com.mannschaft.app.directmail.dto.DirectMailRecipientResponse;
import com.mannschaft.app.directmail.dto.DirectMailResponse;
import com.mannschaft.app.directmail.dto.DirectMailTemplateResponse;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.entity.DirectMailTemplateEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:09+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class DirectMailMapperImpl implements DirectMailMapper {

    @Override
    public DirectMailResponse toMailResponse(DirectMailLogEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        Long senderId = null;
        String subject = null;
        String bodyMarkdown = null;
        String bodyHtml = null;
        String recipientType = null;
        String recipientFilter = null;
        Integer estimatedRecipients = null;
        Integer totalRecipients = null;
        Integer sentCount = null;
        Integer openedCount = null;
        Integer bouncedCount = null;
        String status = null;
        LocalDateTime scheduledAt = null;
        String errorMessage = null;
        LocalDateTime sentAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        senderId = entity.getSenderId();
        subject = entity.getSubject();
        bodyMarkdown = entity.getBodyMarkdown();
        bodyHtml = entity.getBodyHtml();
        recipientType = entity.getRecipientType();
        recipientFilter = entity.getRecipientFilter();
        estimatedRecipients = entity.getEstimatedRecipients();
        totalRecipients = entity.getTotalRecipients();
        sentCount = entity.getSentCount();
        openedCount = entity.getOpenedCount();
        bouncedCount = entity.getBouncedCount();
        status = entity.getStatus();
        scheduledAt = entity.getScheduledAt();
        errorMessage = entity.getErrorMessage();
        sentAt = entity.getSentAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        DirectMailResponse directMailResponse = new DirectMailResponse( id, scopeType, scopeId, senderId, subject, bodyMarkdown, bodyHtml, recipientType, recipientFilter, estimatedRecipients, totalRecipients, sentCount, openedCount, bouncedCount, status, scheduledAt, errorMessage, sentAt, createdAt, updatedAt );

        return directMailResponse;
    }

    @Override
    public List<DirectMailResponse> toMailResponseList(List<DirectMailLogEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<DirectMailResponse> list = new ArrayList<DirectMailResponse>( entities.size() );
        for ( DirectMailLogEntity directMailLogEntity : entities ) {
            list.add( toMailResponse( directMailLogEntity ) );
        }

        return list;
    }

    @Override
    public DirectMailRecipientResponse toRecipientResponse(DirectMailRecipientEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String email = null;
        String status = null;
        LocalDateTime openedAt = null;
        LocalDateTime bouncedAt = null;
        String bounceType = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        email = entity.getEmail();
        status = entity.getStatus();
        openedAt = entity.getOpenedAt();
        bouncedAt = entity.getBouncedAt();
        bounceType = entity.getBounceType();
        createdAt = entity.getCreatedAt();

        DirectMailRecipientResponse directMailRecipientResponse = new DirectMailRecipientResponse( id, userId, email, status, openedAt, bouncedAt, bounceType, createdAt );

        return directMailRecipientResponse;
    }

    @Override
    public List<DirectMailRecipientResponse> toRecipientResponseList(List<DirectMailRecipientEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<DirectMailRecipientResponse> list = new ArrayList<DirectMailRecipientResponse>( entities.size() );
        for ( DirectMailRecipientEntity directMailRecipientEntity : entities ) {
            list.add( toRecipientResponse( directMailRecipientEntity ) );
        }

        return list;
    }

    @Override
    public DirectMailTemplateResponse toTemplateResponse(DirectMailTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String name = null;
        String subject = null;
        String bodyMarkdown = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        name = entity.getName();
        subject = entity.getSubject();
        bodyMarkdown = entity.getBodyMarkdown();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        DirectMailTemplateResponse directMailTemplateResponse = new DirectMailTemplateResponse( id, scopeType, scopeId, name, subject, bodyMarkdown, createdBy, createdAt, updatedAt );

        return directMailTemplateResponse;
    }

    @Override
    public List<DirectMailTemplateResponse> toTemplateResponseList(List<DirectMailTemplateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<DirectMailTemplateResponse> list = new ArrayList<DirectMailTemplateResponse>( entities.size() );
        for ( DirectMailTemplateEntity directMailTemplateEntity : entities ) {
            list.add( toTemplateResponse( directMailTemplateEntity ) );
        }

        return list;
    }
}
