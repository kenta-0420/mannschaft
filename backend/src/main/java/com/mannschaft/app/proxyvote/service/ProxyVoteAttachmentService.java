package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.AttachmentTargetType;
import com.mannschaft.app.proxyvote.AttachmentType;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.AttachmentResponse;
import com.mannschaft.app.proxyvote.entity.ProxyVoteAttachmentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.UUID;

/**
 * 添付ファイルサービス。セッション/議案の添付ファイル管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteAttachmentService {

    private static final Set<String> SESSION_ALLOWED_TYPES = Set.of(
            "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/jpeg", "image/png", "image/webp", "audio/mpeg", "video/mp4", "audio/mp4");
    private static final Set<String> MOTION_ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp");
    private static final int SESSION_MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final int MOTION_MAX_FILE_SIZE = 20 * 1024 * 1024;  // 20MB
    private static final int SESSION_MAX_ATTACHMENTS = 10;
    private static final int MOTION_MAX_ATTACHMENTS = 5;

    private final ProxyVoteSessionService sessionService;
    private final ProxyVoteAttachmentRepository attachmentRepository;
    private final ProxyVoteMapper mapper;

    /**
     * セッションに添付ファイルを追加する。
     */
    @Transactional
    public AttachmentResponse addSessionAttachment(Long sessionId, MultipartFile file,
                                                    String attachmentTypeStr, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        AttachmentType attachmentType = attachmentTypeStr != null
                ? AttachmentType.valueOf(attachmentTypeStr) : AttachmentType.DOCUMENT;

        // ステータスチェック: MINUTES は全ステータスOK、DOCUMENT は DRAFT/OPEN のみ
        if (attachmentType != AttachmentType.MINUTES) {
            if (session.getStatus() != SessionStatus.DRAFT && session.getStatus() != SessionStatus.OPEN) {
                throw new BusinessException(ProxyVoteErrorCode.UPLOAD_NOT_ALLOWED);
            }
        }

        validateFile(file, AttachmentTargetType.SESSION);
        validateAttachmentCount(AttachmentTargetType.SESSION, sessionId, SESSION_MAX_ATTACHMENTS);

        return saveAttachment(AttachmentTargetType.SESSION, sessionId, file, attachmentType, currentUserId);
    }

    /**
     * 議案に添付ファイルを追加する。
     */
    @Transactional
    public AttachmentResponse addMotionAttachment(Long motionId, MultipartFile file,
                                                   String attachmentTypeStr, Long currentUserId) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());

        if (session.getStatus() != SessionStatus.DRAFT && session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.UPLOAD_NOT_ALLOWED);
        }

        AttachmentType attachmentType = attachmentTypeStr != null
                ? AttachmentType.valueOf(attachmentTypeStr) : AttachmentType.DOCUMENT;
        if (attachmentType == AttachmentType.MINUTES) {
            throw new BusinessException(ProxyVoteErrorCode.MINUTES_SESSION_ONLY);
        }

        validateFile(file, AttachmentTargetType.MOTION);
        validateAttachmentCount(AttachmentTargetType.MOTION, motionId, MOTION_MAX_ATTACHMENTS);

        return saveAttachment(AttachmentTargetType.MOTION, motionId, file, attachmentType, currentUserId);
    }

    /**
     * 添付ファイルを削除する。
     */
    @Transactional
    public void deleteAttachment(Long attachmentId) {
        ProxyVoteAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.ATTACHMENT_NOT_FOUND));

        // TODO: S3 からファイル削除
        attachmentRepository.delete(attachment);
        log.info("添付ファイル削除: attachmentId={}", attachmentId);
    }

    private void validateFile(MultipartFile file, AttachmentTargetType targetType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ProxyVoteErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        Set<String> allowedTypes = targetType == AttachmentTargetType.SESSION
                ? SESSION_ALLOWED_TYPES : MOTION_ALLOWED_TYPES;
        if (!allowedTypes.contains(file.getContentType())) {
            throw new BusinessException(ProxyVoteErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        int maxSize = targetType == AttachmentTargetType.SESSION ? SESSION_MAX_FILE_SIZE : MOTION_MAX_FILE_SIZE;
        if (file.getSize() > maxSize) {
            throw new BusinessException(ProxyVoteErrorCode.FILE_SIZE_EXCEEDED);
        }
    }

    private void validateAttachmentCount(AttachmentTargetType targetType, Long targetId, int maxCount) {
        long count = attachmentRepository.countByTargetTypeAndTargetId(targetType, targetId);
        if (count >= maxCount) {
            throw new BusinessException(ProxyVoteErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }
    }

    private AttachmentResponse saveAttachment(AttachmentTargetType targetType, Long targetId,
                                               MultipartFile file, AttachmentType attachmentType, Long currentUserId) {
        // TODO: S3 へアップロード
        String fileKey = "proxy-votes/" + targetType.name().toLowerCase() + "/" + targetId + "/" + UUID.randomUUID();

        long currentCount = attachmentRepository.countByTargetTypeAndTargetId(targetType, targetId);

        ProxyVoteAttachmentEntity attachment = ProxyVoteAttachmentEntity.builder()
                .targetType(targetType)
                .targetId(targetId)
                .fileKey(fileKey)
                .originalFilename(file.getOriginalFilename())
                .fileSize((int) file.getSize())
                .mimeType(file.getContentType())
                .attachmentType(attachmentType)
                .sortOrder((short) currentCount)
                .uploadedBy(currentUserId)
                .build();
        attachment = attachmentRepository.save(attachment);

        log.info("添付ファイル追加: attachmentId={}, targetType={}, targetId={}", attachment.getId(), targetType, targetId);
        return mapper.toAttachmentResponse(attachment);
    }
}
