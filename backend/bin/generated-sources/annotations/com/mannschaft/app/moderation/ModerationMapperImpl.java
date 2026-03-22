package com.mannschaft.app.moderation;

import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
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
public class ModerationMapperImpl implements ModerationMapper {

    @Override
    public ReportResponse toReportResponse(ContentReportEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long targetId = null;
        Long reportedBy = null;
        String scopeType = null;
        Long scopeId = null;
        Long targetUserId = null;
        String description = null;
        String contentSnapshot = null;
        Long reviewedBy = null;
        LocalDateTime reviewedAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        targetId = entity.getTargetId();
        reportedBy = entity.getReportedBy();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        targetUserId = entity.getTargetUserId();
        description = entity.getDescription();
        contentSnapshot = entity.getContentSnapshot();
        reviewedBy = entity.getReviewedBy();
        reviewedAt = entity.getReviewedAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String targetType = entity.getTargetType().name();
        String reason = entity.getReason().name();
        String status = entity.getStatus().name();

        ReportResponse reportResponse = new ReportResponse( id, targetType, targetId, reportedBy, scopeType, scopeId, targetUserId, reason, description, contentSnapshot, status, reviewedBy, reviewedAt, createdAt, updatedAt );

        return reportResponse;
    }

    @Override
    public List<ReportResponse> toReportResponseList(List<ContentReportEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReportResponse> list = new ArrayList<ReportResponse>( entities.size() );
        for ( ContentReportEntity contentReportEntity : entities ) {
            list.add( toReportResponse( contentReportEntity ) );
        }

        return list;
    }
}
