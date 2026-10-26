package com.mannschaft.app.proxyvote;

import com.mannschaft.app.proxyvote.dto.AttachmentResponse;
import com.mannschaft.app.proxyvote.dto.CommentResponse;
import com.mannschaft.app.proxyvote.dto.DelegationResponse;
import com.mannschaft.app.proxyvote.dto.MotionResponse;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteAttachmentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionCommentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * F08.3 議決権行使・委任状の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ProxyVoteMapper {

    @Mapping(target = "votingStatus", expression = "java(entity.getVotingStatus().name())")
    @Mapping(target = "requiredApproval", expression = "java(entity.getRequiredApproval().name())")
    @Mapping(target = "result", expression = "java(entity.getResult() != null ? entity.getResult().name() : null)")
    MotionResponse toMotionResponse(ProxyVoteMotionEntity entity);

    List<MotionResponse> toMotionResponseList(List<ProxyVoteMotionEntity> entities);

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    @Mapping(target = "attachmentType", expression = "java(entity.getAttachmentType().name())")
    AttachmentResponse toAttachmentResponse(ProxyVoteAttachmentEntity entity);

    List<AttachmentResponse> toAttachmentResponseList(List<ProxyVoteAttachmentEntity> entities);

    CommentResponse toCommentResponse(ProxyVoteMotionCommentEntity entity);

    List<CommentResponse> toCommentResponseList(List<ProxyVoteMotionCommentEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    DelegationResponse toDelegationResponse(ProxyDelegationEntity entity);

    List<DelegationResponse> toDelegationResponseList(List<ProxyDelegationEntity> entities);
}
