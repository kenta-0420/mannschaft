package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.MarkdownConverter;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.directmail.DirectMailErrorCode;
import com.mannschaft.app.directmail.DirectMailMapper;
import com.mannschaft.app.directmail.dto.CreateDirectMailRequest;
import com.mannschaft.app.directmail.dto.DirectMailRecipientResponse;
import com.mannschaft.app.directmail.dto.DirectMailResponse;
import com.mannschaft.app.directmail.dto.DirectMailStatsResponse;
import com.mannschaft.app.directmail.dto.EstimateRecipientsRequest;
import com.mannschaft.app.directmail.dto.EstimateRecipientsResponse;
import com.mannschaft.app.directmail.dto.PreviewMailRequest;
import com.mannschaft.app.directmail.dto.PreviewMailResponse;
import com.mannschaft.app.directmail.dto.ScheduleMailRequest;
import com.mannschaft.app.directmail.dto.UpdateDirectMailRequest;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.directmail.repository.DirectMailRecipientRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ダイレクトメールサービス。メールのCRUD・送信・統計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMailService {

    private final DirectMailLogRepository mailLogRepository;
    private final DirectMailRecipientRepository recipientRepository;
    private final DirectMailMapper directMailMapper;
    private final UserRoleRepository userRoleRepository;

    /**
     * メールを作成する（下書き保存）。
     */
    @Transactional
    public DirectMailResponse createMail(String scopeType, Long scopeId, Long senderId,
                                          CreateDirectMailRequest request) {
        DirectMailLogEntity entity = DirectMailLogEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .senderId(senderId)
                .subject(request.getSubject())
                .bodyMarkdown(request.getBodyMarkdown())
                .bodyHtml(request.getBodyHtml())
                .recipientType(request.getRecipientType())
                .recipientFilter(request.getRecipientFilter())
                .estimatedRecipients(request.getEstimatedRecipients())
                .build();

        DirectMailLogEntity saved = mailLogRepository.save(entity);
        log.info("ダイレクトメール作成: scopeType={}, scopeId={}, mailId={}", scopeType, scopeId, saved.getId());
        return directMailMapper.toMailResponse(saved);
    }

    /**
     * メール一覧を取得する。
     */
    public PagedResponse<DirectMailResponse> listMails(String scopeType, Long scopeId, Pageable pageable) {
        Page<DirectMailLogEntity> page = mailLogRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId, pageable);
        List<DirectMailResponse> content = directMailMapper.toMailResponseList(page.getContent());
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(content, meta);
    }

    /**
     * メール詳細を取得する。
     */
    public DirectMailResponse getMail(String scopeType, Long scopeId, Long mailId) {
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);
        return directMailMapper.toMailResponse(entity);
    }

    /**
     * メールを編集する（下書きのみ）。
     */
    @Transactional
    public DirectMailResponse updateMail(String scopeType, Long scopeId, Long mailId,
                                          UpdateDirectMailRequest request) {
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException(DirectMailErrorCode.NOT_DRAFT);
        }

        entity.update(
                request.getSubject(),
                request.getBodyMarkdown(),
                request.getBodyHtml(),
                request.getRecipientType(),
                request.getRecipientFilter(),
                request.getEstimatedRecipients()
        );

        DirectMailLogEntity saved = mailLogRepository.save(entity);
        log.info("ダイレクトメール更新: mailId={}", mailId);
        return directMailMapper.toMailResponse(saved);
    }

    /**
     * メールを即時送信する。
     */
    @Transactional
    public DirectMailResponse sendMail(String scopeType, Long scopeId, Long mailId) {
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);
        if (!"DRAFT".equals(entity.getStatus()) && !"SCHEDULED".equals(entity.getStatus())) {
            throw new BusinessException(DirectMailErrorCode.ALREADY_SENT);
        }

        entity.markSending();
        DirectMailLogEntity saved = mailLogRepository.save(entity);
        log.info("ダイレクトメール送信開始: mailId={}", mailId);

        // TODO: 非同期で実際のSES送信処理を実行（ApplicationEvent発行）
        return directMailMapper.toMailResponse(saved);
    }

    /**
     * メールを予約送信する。
     */
    @Transactional
    public DirectMailResponse scheduleMail(String scopeType, Long scopeId, Long mailId,
                                            ScheduleMailRequest request) {
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException(DirectMailErrorCode.NOT_DRAFT);
        }

        entity.schedule(request.getScheduledAt());
        DirectMailLogEntity saved = mailLogRepository.save(entity);
        log.info("ダイレクトメール予約: mailId={}, scheduledAt={}", mailId, request.getScheduledAt());
        return directMailMapper.toMailResponse(saved);
    }

    /**
     * 送信をキャンセルする。
     */
    @Transactional
    public DirectMailResponse cancelMail(String scopeType, Long scopeId, Long mailId) {
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);
        if ("SENDING".equals(entity.getStatus()) || "SENT".equals(entity.getStatus())) {
            throw new BusinessException(DirectMailErrorCode.CANNOT_CANCEL);
        }

        entity.cancel();
        DirectMailLogEntity saved = mailLogRepository.save(entity);
        log.info("ダイレクトメールキャンセル: mailId={}", mailId);
        return directMailMapper.toMailResponse(saved);
    }

    /**
     * 受信者一覧を取得する。
     */
    public PagedResponse<DirectMailRecipientResponse> listRecipients(String scopeType, Long scopeId,
                                                                      Long mailId, Pageable pageable) {
        findMailOrThrow(scopeType, scopeId, mailId);
        Page<DirectMailRecipientEntity> page = recipientRepository.findByMailLogId(mailId, pageable);
        List<DirectMailRecipientResponse> content = directMailMapper.toRecipientResponseList(page.getContent());
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(content, meta);
    }

    /**
     * 送信統計を取得する。
     */
    public DirectMailStatsResponse getStats(String scopeType, Long scopeId, Long mailId) {
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);

        double openRate = entity.getTotalRecipients() > 0
                ? (double) entity.getOpenedCount() / entity.getTotalRecipients() * 100 : 0.0;
        double bounceRate = entity.getTotalRecipients() > 0
                ? (double) entity.getBouncedCount() / entity.getTotalRecipients() * 100 : 0.0;

        return new DirectMailStatsResponse(
                entity.getId(),
                entity.getTotalRecipients(),
                entity.getSentCount(),
                entity.getOpenedCount(),
                entity.getBouncedCount(),
                openRate,
                bounceRate
        );
    }

    /**
     * メールプレビューを生成する。
     */
    public PreviewMailResponse preview(PreviewMailRequest request) {
        String html = MarkdownConverter.toHtml(request.getBodyMarkdown());
        return new PreviewMailResponse(html);
    }

    /**
     * 配信対象数を見積もる。
     */
    public EstimateRecipientsResponse estimateRecipients(String scopeType, Long scopeId,
                                                          EstimateRecipientsRequest request) {
        // 簡易実装: scopeType に基づくスコープ内全メンバー数を返却
        // 将来実装: recipientType と recipientFilter に基づく詳細なフィルタリング
        int estimated;
        if ("TEAM".equals(scopeType)) {
            estimated = (int) userRoleRepository.countByTeamId(scopeId);
        } else if ("ORGANIZATION".equals(scopeType)) {
            estimated = (int) userRoleRepository.countByOrganizationId(scopeId);
        } else {
            estimated = 0;
        }
        log.info("配信対象数見積: scopeType={}, scopeId={}, type={}, estimated={}", scopeType, scopeId, request.getRecipientType(), estimated);
        return new EstimateRecipientsResponse(estimated);
    }

    private DirectMailLogEntity findMailOrThrow(String scopeType, Long scopeId, Long mailId) {
        return mailLogRepository.findByIdAndScopeTypeAndScopeId(mailId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(DirectMailErrorCode.MAIL_NOT_FOUND));
    }
}
