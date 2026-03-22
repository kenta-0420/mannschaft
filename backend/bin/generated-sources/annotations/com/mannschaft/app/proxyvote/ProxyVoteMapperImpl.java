package com.mannschaft.app.proxyvote;

import com.mannschaft.app.proxyvote.dto.AttachmentResponse;
import com.mannschaft.app.proxyvote.dto.CommentResponse;
import com.mannschaft.app.proxyvote.dto.DelegationResponse;
import com.mannschaft.app.proxyvote.dto.MotionResponse;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteAttachmentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionCommentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
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
public class ProxyVoteMapperImpl implements ProxyVoteMapper {

    @Override
    public MotionResponse toMotionResponse(ProxyVoteMotionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        MotionResponse.MotionResponseBuilder motionResponse = MotionResponse.builder();

        motionResponse.abstainCount( entity.getAbstainCount() );
        motionResponse.approveCount( entity.getApproveCount() );
        motionResponse.description( entity.getDescription() );
        motionResponse.id( entity.getId() );
        motionResponse.motionNumber( entity.getMotionNumber() );
        motionResponse.rejectCount( entity.getRejectCount() );
        motionResponse.title( entity.getTitle() );
        motionResponse.voteDeadlineAt( entity.getVoteDeadlineAt() );

        motionResponse.votingStatus( entity.getVotingStatus().name() );
        motionResponse.requiredApproval( entity.getRequiredApproval().name() );
        motionResponse.result( entity.getResult() != null ? entity.getResult().name() : null );

        return motionResponse.build();
    }

    @Override
    public List<MotionResponse> toMotionResponseList(List<ProxyVoteMotionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MotionResponse> list = new ArrayList<MotionResponse>( entities.size() );
        for ( ProxyVoteMotionEntity proxyVoteMotionEntity : entities ) {
            list.add( toMotionResponse( proxyVoteMotionEntity ) );
        }

        return list;
    }

    @Override
    public AttachmentResponse toAttachmentResponse(ProxyVoteAttachmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        AttachmentResponse.AttachmentResponseBuilder attachmentResponse = AttachmentResponse.builder();

        attachmentResponse.createdAt( entity.getCreatedAt() );
        attachmentResponse.fileSize( entity.getFileSize() );
        attachmentResponse.id( entity.getId() );
        attachmentResponse.mimeType( entity.getMimeType() );
        attachmentResponse.originalFilename( entity.getOriginalFilename() );
        attachmentResponse.uploadedBy( entity.getUploadedBy() );

        attachmentResponse.targetType( entity.getTargetType().name() );
        attachmentResponse.attachmentType( entity.getAttachmentType().name() );

        return attachmentResponse.build();
    }

    @Override
    public List<AttachmentResponse> toAttachmentResponseList(List<ProxyVoteAttachmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AttachmentResponse> list = new ArrayList<AttachmentResponse>( entities.size() );
        for ( ProxyVoteAttachmentEntity proxyVoteAttachmentEntity : entities ) {
            list.add( toAttachmentResponse( proxyVoteAttachmentEntity ) );
        }

        return list;
    }

    @Override
    public CommentResponse toCommentResponse(ProxyVoteMotionCommentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        CommentResponse.CommentResponseBuilder commentResponse = CommentResponse.builder();

        commentResponse.body( entity.getBody() );
        commentResponse.createdAt( entity.getCreatedAt() );
        commentResponse.id( entity.getId() );
        commentResponse.userId( entity.getUserId() );

        return commentResponse.build();
    }

    @Override
    public List<CommentResponse> toCommentResponseList(List<ProxyVoteMotionCommentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CommentResponse> list = new ArrayList<CommentResponse>( entities.size() );
        for ( ProxyVoteMotionCommentEntity proxyVoteMotionCommentEntity : entities ) {
            list.add( toCommentResponse( proxyVoteMotionCommentEntity ) );
        }

        return list;
    }

    @Override
    public DelegationResponse toDelegationResponse(ProxyDelegationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        DelegationResponse.DelegationResponseBuilder delegationResponse = DelegationResponse.builder();

        delegationResponse.createdAt( entity.getCreatedAt() );
        delegationResponse.delegateId( entity.getDelegateId() );
        delegationResponse.delegatorId( entity.getDelegatorId() );
        delegationResponse.electronicSealId( entity.getElectronicSealId() );
        delegationResponse.id( entity.getId() );
        delegationResponse.isBlank( entity.getIsBlank() );
        delegationResponse.reason( entity.getReason() );
        delegationResponse.reviewedAt( entity.getReviewedAt() );
        delegationResponse.reviewedBy( entity.getReviewedBy() );
        delegationResponse.sessionId( entity.getSessionId() );

        delegationResponse.status( entity.getStatus().name() );

        return delegationResponse.build();
    }

    @Override
    public List<DelegationResponse> toDelegationResponseList(List<ProxyDelegationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<DelegationResponse> list = new ArrayList<DelegationResponse>( entities.size() );
        for ( ProxyDelegationEntity proxyDelegationEntity : entities ) {
            list.add( toDelegationResponse( proxyDelegationEntity ) );
        }

        return list;
    }
}
