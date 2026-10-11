package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.AttachmentDownloadUrlResponse;
import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 関連詳細/添付の最新 F00 と準備中判定を leaf の読取 TX の外で行う。
 * 旧来の所属認可と他 scope の閲覧認可は leaf に残し、作成・更新・一覧を変更しない。
 */
@Service
@RequiredArgsConstructor
public class BulletinReadFacade {
    private final BulletinThreadService threadService;
    private final BulletinAttachmentService attachmentService;
    private final BulletinAccessGuard accessGuard;
    private final ContentVisibilityChecker visibilityChecker;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    public ThreadResponse getThread(ScopeType type, Long scopeId, Long threadId, Long viewer) {
        var metadata = threadService.getReadMetadata(threadId);
        if (!metadata.scopeType().equals(type.name()) || !Objects.equals(metadata.scopeId(), scopeId)) throw notFound();
        assertReadable(metadata, viewer);
        return threadService.getThread(type, scopeId, threadId, viewer);
    }

    public ThreadResponse getThreadGlobal(Long threadId, Long viewer) {
        assertReadable(threadService.getReadMetadata(threadId), viewer);
        return threadService.getThreadGlobal(threadId, viewer);
    }

    public BulletinThreadService.PreviewMetadata getPreviewMetadata(Long threadId, Long viewer) {
        var metadata = threadService.getReadMetadata(threadId);
        if (!isManaged(metadata)) throw notFound();
        assertReadable(metadata, viewer);
        return metadata;
    }

    public List<AttachmentResponse> listThreadAttachments(Long threadId, Long viewer) {
        assertReadable(threadService.getReadMetadata(threadId), viewer);
        return attachmentService.listThreadAttachments(threadId, viewer);
    }

    public List<AttachmentResponse> listReplyAttachments(Long replyId, Long viewer) {
        assertReadable(attachmentService.getReplyReadMetadata(replyId), viewer);
        return attachmentService.listReplyAttachments(replyId, viewer);
    }

    public AttachmentDownloadUrlResponse generateDownloadUrl(Long attachmentId, Long viewer) {
        assertReadable(attachmentService.getAttachmentReadMetadata(attachmentId), viewer);
        return attachmentService.generateDownloadUrl(attachmentId, viewer);
    }

    private void assertReadable(BulletinThreadService.PreviewMetadata metadata, Long viewer) {
        if (!isManaged(metadata)) return;
        ScopeType type = ScopeType.valueOf(metadata.scopeType());
        try {
            accessGuard.checkMembership(viewer, type, metadata.scopeId());
            if ((type == ScopeType.TEAM && teamService.isProvisioned(metadata.scopeId()))
                    || (type == ScopeType.ORGANIZATION && organizationService.isProvisioned(metadata.scopeId()))) throw notFound();
            visibilityChecker.assertCanView(ReferenceType.BULLETIN_THREAD, metadata.threadId(), viewer);
        } catch (BusinessException e) {
            String code = e.getErrorCode().getCode();
            if (code.equals("COMMON_002") || code.equals("VISIBILITY_001") || code.equals("VISIBILITY_004")) throw notFound();
            throw e;
        }
    }

    private static boolean isManaged(BulletinThreadService.PreviewMetadata metadata) {
        return "TEAM".equals(metadata.scopeType()) || "ORGANIZATION".equals(metadata.scopeType());
    }
    private static BusinessException notFound() {
        return new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND);
    }
}
