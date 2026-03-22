package com.mannschaft.app.service;

import com.mannschaft.app.service.dto.AttachmentResponse;
import com.mannschaft.app.service.dto.FieldResponse;
import com.mannschaft.app.service.dto.SettingsResponse;
import com.mannschaft.app.service.entity.ServiceRecordAttachmentEntity;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ServiceRecordMapperImpl implements ServiceRecordMapper {

    @Override
    public FieldResponse toFieldResponse(ServiceRecordFieldEntity entity) {
        if ( entity == null ) {
            return null;
        }

        FieldResponse.FieldResponseBuilder fieldResponse = FieldResponse.builder();

        fieldResponse.fieldType( fieldTypeToString( entity.getFieldType() ) );
        fieldResponse.options( jsonToStringList( entity.getOptions() ) );
        fieldResponse.description( entity.getDescription() );
        fieldResponse.fieldName( entity.getFieldName() );
        fieldResponse.id( entity.getId() );
        fieldResponse.isActive( entity.getIsActive() );
        fieldResponse.isRequired( entity.getIsRequired() );
        fieldResponse.sortOrder( entity.getSortOrder() );

        return fieldResponse.build();
    }

    @Override
    public SettingsResponse toSettingsResponse(ServiceRecordSettingsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        SettingsResponse.SettingsResponseBuilder settingsResponse = SettingsResponse.builder();

        settingsResponse.teamId( entity.getTeamId() );
        settingsResponse.isDashboardEnabled( entity.getIsDashboardEnabled() );
        settingsResponse.isReactionEnabled( entity.getIsReactionEnabled() );

        return settingsResponse.build();
    }

    @Override
    public AttachmentResponse toAttachmentResponse(ServiceRecordAttachmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        AttachmentResponse.AttachmentResponseBuilder attachmentResponse = AttachmentResponse.builder();

        attachmentResponse.contentType( entity.getContentType() );
        attachmentResponse.createdAt( entity.getCreatedAt() );
        attachmentResponse.fileName( entity.getFileName() );
        attachmentResponse.fileSize( entity.getFileSize() );
        attachmentResponse.id( entity.getId() );
        attachmentResponse.sortOrder( entity.getSortOrder() );

        return attachmentResponse.build();
    }
}
