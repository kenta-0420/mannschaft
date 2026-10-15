package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminFeedbackErrorCode;
import com.mannschaft.app.admin.AnnouncementFeedbackMapper;
import com.mannschaft.app.admin.dto.AnnouncementResponse;
import com.mannschaft.app.admin.dto.CreateAnnouncementRequest;
import com.mannschaft.app.admin.dto.UpdateAnnouncementRequest;
import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * プラットフォームお知らせサービス。お知らせのCRUD・公開を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAnnouncementService {

    private final PlatformAnnouncementRepository announcementRepository;
    private final AnnouncementFeedbackMapper mapper;

    /**
     * 全お知らせをページネーション付きで取得する（管理者向け）。
     *
     * @param pageable ページネーション情報
     * @return お知らせページ
     */
    public Page<AnnouncementResponse> getAllAnnouncements(Pageable pageable) {
        return announcementRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(mapper::toAnnouncementResponse);
    }

    /**
     * 公開済みかつ有効期限内のお知らせを取得する。
     *
     * @return 有効なお知らせ一覧
     */
    public List<AnnouncementResponse> getActiveAnnouncements() {
        return mapper.toAnnouncementResponseList(
                announcementRepository.findActiveAnnouncements(LocalDateTime.now()));
    }

    /**
     * お知らせを作成する。
     *
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたお知らせ
     */
    @Transactional
    public AnnouncementResponse createAnnouncement(CreateAnnouncementRequest req, Long userId) {
        PlatformAnnouncementEntity entity = PlatformAnnouncementEntity.builder()
                .title(req.getTitle())
                .body(req.getBody())
                .priority(req.getPriority() != null ? req.getPriority() : "NORMAL")
                .targetScope(req.getTargetScope() != null ? req.getTargetScope() : "ALL")
                .isPinned(req.getIsPinned() != null ? req.getIsPinned() : false)
                .expiresAt(req.getExpiresAt())
                .createdBy(userId)
                .build();

        entity = announcementRepository.save(entity);
        log.info("お知らせ作成: id={}, title={}, userId={}", entity.getId(), entity.getTitle(), userId);
        return mapper.toAnnouncementResponse(entity);
    }

    /**
     * お知らせを更新する。
     *
     * @param id  お知らせID
     * @param req 更新リクエスト
     * @return 更新後のお知らせ
     */
    @Transactional
    public AnnouncementResponse updateAnnouncement(Long id, UpdateAnnouncementRequest req) {
        PlatformAnnouncementEntity entity = announcementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.ANNOUNCEMENT_NOT_FOUND));

        entity.update(
                req.getTitle(),
                req.getBody(),
                req.getPriority() != null ? req.getPriority() : entity.getPriority(),
                req.getTargetScope() != null ? req.getTargetScope() : entity.getTargetScope(),
                req.getIsPinned() != null ? req.getIsPinned() : entity.getIsPinned(),
                req.getExpiresAt()
        );
        entity = announcementRepository.save(entity);
        log.info("お知らせ更新: id={}", id);
        return mapper.toAnnouncementResponse(entity);
    }

    /**
     * お知らせを公開する。
     *
     * @param id お知らせID
     * @return 公開後のお知らせ
     */
    @Transactional
    public AnnouncementResponse publishAnnouncement(Long id) {
        PlatformAnnouncementEntity entity = announcementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.ANNOUNCEMENT_NOT_FOUND));

        if (entity.getPublishedAt() != null) {
            throw new BusinessException(AdminFeedbackErrorCode.ANNOUNCEMENT_ALREADY_PUBLISHED);
        }

        entity.publish();
        entity = announcementRepository.save(entity);
        log.info("お知らせ公開: id={}", id);
        return mapper.toAnnouncementResponse(entity);
    }

    /**
     * お知らせを論理削除する。
     *
     * @param id お知らせID
     */
    @Transactional
    public void deleteAnnouncement(Long id) {
        PlatformAnnouncementEntity entity = announcementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.ANNOUNCEMENT_NOT_FOUND));

        entity.softDelete();
        announcementRepository.save(entity);
        log.info("お知らせ削除: id={}", id);
    }
}
