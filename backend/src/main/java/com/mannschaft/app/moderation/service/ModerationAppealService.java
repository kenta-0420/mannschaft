package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.AppealStatus;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.AppealResponse;
import com.mannschaft.app.moderation.entity.ModerationAppealEntity;
import com.mannschaft.app.moderation.repository.ModerationAppealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 異議申立てサービス。INVITED→PENDING→ACCEPTED/REJECTEDのフローを管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModerationAppealService {

    private final ModerationAppealRepository appealRepository;
    private final ModerationExtMapper mapper;

    /**
     * 異議申立て詳細を取得する（トークン認証）。
     *
     * @param id    異議申立てID
     * @param token 認証トークン
     * @return 異議申立てレスポンス
     */
    public AppealResponse getAppeal(Long id, String token) {
        ModerationAppealEntity appeal = appealRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.APPEAL_NOT_FOUND));

        if (!java.security.MessageDigest.isEqual(
                appeal.getAppealToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new BusinessException(ModerationExtErrorCode.APPEAL_TOKEN_INVALID);
        }

        if (appeal.getAppealTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ModerationExtErrorCode.APPEAL_TOKEN_INVALID);
        }

        return mapper.toAppealResponse(appeal);
    }

    /**
     * 異議申立てIDで詳細を取得する（管理者用）。
     *
     * @param id 異議申立てID
     * @return 異議申立てレスポンス
     */
    public AppealResponse getAppealById(Long id) {
        ModerationAppealEntity appeal = appealRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.APPEAL_NOT_FOUND));
        return mapper.toAppealResponse(appeal);
    }

    /**
     * 異議申立て理由を送信する（INVITED→PENDING）。
     *
     * @param id           異議申立てID
     * @param appealReason 申立て理由
     * @param token        認証トークン
     * @return 更新後の異議申立てレスポンス
     */
    @Transactional
    public AppealResponse submitAppeal(Long id, String appealReason, String token) {
        ModerationAppealEntity appeal = appealRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.APPEAL_NOT_FOUND));

        if (!java.security.MessageDigest.isEqual(
                appeal.getAppealToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new BusinessException(ModerationExtErrorCode.APPEAL_TOKEN_INVALID);
        }

        if (appeal.getAppealTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ModerationExtErrorCode.APPEAL_TOKEN_INVALID);
        }

        if (appeal.getStatus() != AppealStatus.INVITED) {
            throw new BusinessException(ModerationExtErrorCode.APPEAL_ALREADY_SUBMITTED);
        }

        appeal.submit(appealReason);
        appealRepository.save(appeal);

        log.info("異議申立て送信: id={}, userId={}", id, appeal.getUserId());
        return mapper.toAppealResponse(appeal);
    }

    /**
     * 異議申立てをレビューする（SYSTEM_ADMIN用）。
     *
     * @param id         異議申立てID
     * @param status     新ステータス（ACCEPTED/REJECTED）
     * @param reviewNote レビューメモ
     * @param reviewerId レビュアーID
     * @return 更新後の異議申立てレスポンス
     */
    @Transactional
    public AppealResponse reviewAppeal(Long id, String status, String reviewNote, Long reviewerId) {
        ModerationAppealEntity appeal = appealRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.APPEAL_NOT_FOUND));

        if (appeal.getStatus() != AppealStatus.PENDING) {
            throw new BusinessException(ModerationExtErrorCode.APPEAL_INVALID_STATUS);
        }

        AppealStatus newStatus = AppealStatus.valueOf(status);
        appeal.review(reviewerId, reviewNote, newStatus);
        appealRepository.save(appeal);

        log.info("異議申立てレビュー: id={}, newStatus={}, reviewerId={}", id, newStatus, reviewerId);
        return mapper.toAppealResponse(appeal);
    }

    /**
     * 異議申立て一覧を取得する（ページング付き）。
     *
     * @param pageable ページング情報
     * @return ページング済み異議申立て一覧
     */
    public Page<AppealResponse> getAppeals(Pageable pageable) {
        return appealRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(mapper::toAppealResponse);
    }

    /**
     * PENDING状態の異議申立て数を取得する。
     *
     * @return 件数
     */
    public long countPendingAppeals() {
        return appealRepository.countByStatus(AppealStatus.PENDING);
    }
}
