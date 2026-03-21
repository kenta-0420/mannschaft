package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CommentResponse;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 回覧板機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface CirculationMapper {

    @Mapping(target = "circulationMode", expression = "java(entity.getCirculationMode().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "priority", expression = "java(entity.getPriority().name())")
    @Mapping(target = "stampDisplayStyle", expression = "java(entity.getStampDisplayStyle().name())")
    DocumentResponse toDocumentResponse(CirculationDocumentEntity entity);

    List<DocumentResponse> toDocumentResponseList(List<CirculationDocumentEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    RecipientResponse toRecipientResponse(CirculationRecipientEntity entity);

    List<RecipientResponse> toRecipientResponseList(List<CirculationRecipientEntity> entities);

    AttachmentResponse toAttachmentResponse(CirculationAttachmentEntity entity);

    List<AttachmentResponse> toAttachmentResponseList(List<CirculationAttachmentEntity> entities);

    CommentResponse toCommentResponse(CirculationCommentEntity entity);

    List<CommentResponse> toCommentResponseList(List<CirculationCommentEntity> entities);
}
