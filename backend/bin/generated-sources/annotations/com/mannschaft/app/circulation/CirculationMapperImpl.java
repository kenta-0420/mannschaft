package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CommentResponse;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:08+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class CirculationMapperImpl implements CirculationMapper {

    @Override
    public DocumentResponse toDocumentResponse(CirculationDocumentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        Long createdBy = null;
        String title = null;
        String body = null;
        Integer sequentialCount = null;
        LocalDate dueDate = null;
        Boolean reminderEnabled = null;
        Short reminderIntervalHours = null;
        Integer totalRecipientCount = null;
        Integer stampedCount = null;
        LocalDateTime completedAt = null;
        Integer attachmentCount = null;
        Integer commentCount = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        createdBy = entity.getCreatedBy();
        title = entity.getTitle();
        body = entity.getBody();
        sequentialCount = entity.getSequentialCount();
        dueDate = entity.getDueDate();
        reminderEnabled = entity.getReminderEnabled();
        reminderIntervalHours = entity.getReminderIntervalHours();
        totalRecipientCount = entity.getTotalRecipientCount();
        stampedCount = entity.getStampedCount();
        completedAt = entity.getCompletedAt();
        attachmentCount = entity.getAttachmentCount();
        commentCount = entity.getCommentCount();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String circulationMode = entity.getCirculationMode().name();
        String status = entity.getStatus().name();
        String priority = entity.getPriority().name();
        String stampDisplayStyle = entity.getStampDisplayStyle().name();

        DocumentResponse documentResponse = new DocumentResponse( id, scopeType, scopeId, createdBy, title, body, circulationMode, sequentialCount, status, priority, dueDate, reminderEnabled, reminderIntervalHours, stampDisplayStyle, totalRecipientCount, stampedCount, completedAt, attachmentCount, commentCount, createdAt, updatedAt );

        return documentResponse;
    }

    @Override
    public List<DocumentResponse> toDocumentResponseList(List<CirculationDocumentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<DocumentResponse> list = new ArrayList<DocumentResponse>( entities.size() );
        for ( CirculationDocumentEntity circulationDocumentEntity : entities ) {
            list.add( toDocumentResponse( circulationDocumentEntity ) );
        }

        return list;
    }

    @Override
    public RecipientResponse toRecipientResponse(CirculationRecipientEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long documentId = null;
        Long userId = null;
        Integer sortOrder = null;
        LocalDateTime stampedAt = null;
        Long sealId = null;
        String sealVariant = null;
        Short tiltAngle = null;
        Boolean isFlipped = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        documentId = entity.getDocumentId();
        userId = entity.getUserId();
        sortOrder = entity.getSortOrder();
        stampedAt = entity.getStampedAt();
        sealId = entity.getSealId();
        sealVariant = entity.getSealVariant();
        tiltAngle = entity.getTiltAngle();
        isFlipped = entity.getIsFlipped();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();

        RecipientResponse recipientResponse = new RecipientResponse( id, documentId, userId, sortOrder, status, stampedAt, sealId, sealVariant, tiltAngle, isFlipped, createdAt, updatedAt );

        return recipientResponse;
    }

    @Override
    public List<RecipientResponse> toRecipientResponseList(List<CirculationRecipientEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<RecipientResponse> list = new ArrayList<RecipientResponse>( entities.size() );
        for ( CirculationRecipientEntity circulationRecipientEntity : entities ) {
            list.add( toRecipientResponse( circulationRecipientEntity ) );
        }

        return list;
    }

    @Override
    public AttachmentResponse toAttachmentResponse(CirculationAttachmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long documentId = null;
        String fileKey = null;
        String originalFilename = null;
        Long fileSize = null;
        String mimeType = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        documentId = entity.getDocumentId();
        fileKey = entity.getFileKey();
        originalFilename = entity.getOriginalFilename();
        fileSize = entity.getFileSize();
        mimeType = entity.getMimeType();
        createdAt = entity.getCreatedAt();

        AttachmentResponse attachmentResponse = new AttachmentResponse( id, documentId, fileKey, originalFilename, fileSize, mimeType, createdAt );

        return attachmentResponse;
    }

    @Override
    public List<AttachmentResponse> toAttachmentResponseList(List<CirculationAttachmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AttachmentResponse> list = new ArrayList<AttachmentResponse>( entities.size() );
        for ( CirculationAttachmentEntity circulationAttachmentEntity : entities ) {
            list.add( toAttachmentResponse( circulationAttachmentEntity ) );
        }

        return list;
    }

    @Override
    public CommentResponse toCommentResponse(CirculationCommentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long documentId = null;
        Long userId = null;
        String body = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        documentId = entity.getDocumentId();
        userId = entity.getUserId();
        body = entity.getBody();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        CommentResponse commentResponse = new CommentResponse( id, documentId, userId, body, createdAt, updatedAt );

        return commentResponse;
    }

    @Override
    public List<CommentResponse> toCommentResponseList(List<CirculationCommentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CommentResponse> list = new ArrayList<CommentResponse>( entities.size() );
        for ( CirculationCommentEntity circulationCommentEntity : entities ) {
            list.add( toCommentResponse( circulationCommentEntity ) );
        }

        return list;
    }
}
