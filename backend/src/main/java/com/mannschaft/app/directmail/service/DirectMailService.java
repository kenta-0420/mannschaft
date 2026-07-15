package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.MarkdownConverter;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.directmail.DirectMailErrorCode;
import com.mannschaft.app.directmail.DirectMailMapper;
import com.mannschaft.app.directmail.event.DirectMailSendEvent;
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
import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * ダイレクトメールサービス。メールのCRUD・送信・統計を担当する。
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: 全公開メソッドの入口で {@link AccessControlService} による
 * 認可検証を行う（閲覧系=checkMembership／変更系・受信者PII・見積=checkAdminOrAbove）。
 * 対象エンティティは (id, scopeType, scopeId) 複合条件でフェッチするため、path スコープと
 * entity スコープの不一致（BOLA）は {@link DirectMailErrorCode#MAIL_NOT_FOUND} → 404 で存在秘匿される。
 * {@link #sendSystemAdMail} のみシステム内部呼び出し（F09.17 広告経路）のため対象外。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMailService {

    /** 認可根治戦役 Wave2 トランシェ2C: スコープ認可基盤 */
    private final AccessControlService accessControlService;

    private final DirectMailLogRepository mailLogRepository;
    private final DirectMailRecipientRepository recipientRepository;
    private final DirectMailMapper directMailMapper;
    private final UserRoleRepository userRoleRepository;
    private final DomainEventPublisher eventPublisher;
    /** F09.13 通知クレジット消費（ダイレクトメールは課金対象） */
    private final NotificationCreditService notificationCreditService;
    /** F09.18 Phase 18-c TC-4: EmailOutboxService 経由で SES 送信する。 */
    private final EmailOutboxService emailOutboxService;

    /** F09.17 Phase 11-b ε-B 広告メール送信者種別。{@code sender_type='SYSTEM_AD'}。 */
    private static final String SENDER_TYPE_SYSTEM_AD = "SYSTEM_AD";

    /** F09.17 Phase 11-b ε-B 広告メール scope_type. 広告は組織横断のため固定値。 */
    private static final String AD_SCOPE_TYPE = "ADVERTISER_AD";

    /** F09.17 Phase 11-b ε-B 件名プレフィックス（景品表示法対応・運営層強制）。 */
    private static final String AD_SUBJECT_PREFIX = "[PR] ";

    /**
     * F09.18 Phase 18-f: {@link #sendSystemAdMail} の戻り値。
     * recipient と outboxId を呼び出し元（{@code AdEmailChannelService}）に返す。
     *
     * @param recipient 作成された {@link DirectMailRecipientEntity}
     * @param outboxId  {@code email_outbox.id}（双方向トレース用）
     */
    public record AdMailSendResult(DirectMailRecipientEntity recipient, UUID outboxId) {}

    /**
     * F09.17 Phase 11-b ε-B 広告メール送信。
     *
     * <p>処理:</p>
     * <ol>
     *   <li>{@code direct_mail_logs} 行を 1 件作成（sender_type=SYSTEM_AD、scope_type=ADVERTISER_AD、
     *       scope_id=advertiserAccountId、senderId=systemSenderId、status=SENDING）</li>
     *   <li>{@code direct_mail_recipients} 行を 1 件作成</li>
     *   <li>件名に {@link #AD_SUBJECT_PREFIX "[PR] "} を強制付与</li>
     *   <li>{@link EmailOutboxService#enqueue} で SES 送信（F09.18 outbox 経由・非同期・リトライ保証）</li>
     *   <li>SES message_id を recipient.sesMessageId に記録</li>
     *   <li>log.status を SENT に更新</li>
     * </ol>
     *
     * <p>呼び出し元（{@code AdEmailChannelService}）は戻り値 {@link AdMailSendResult} から
     * recipientId を {@code ad_email_deliveries.direct_mail_recipient_id} に、
     * outboxId を {@code ad_email_deliveries.email_outbox_id} に転記する（双方向トレース）。</p>
     *
     * <p>本メソッドは {@code @Transactional}。SES 送信は outbox 経由の非同期であり、
     * enqueue 失敗時は例外が伝播する。真のバウンス検知は既存 {@code SesWebhookService} 経路で行う。</p>
     *
     * @param advertiserAccountId  広告主アカウント ID（scope_id として使用）
     * @param userId               受信者ユーザー ID
     * @param recipientEmail       送信先メールアドレス
     * @param subject              件名（[PR] プレフィックスは内部で付与する。重複時はそのまま）
     * @param bodyHtml             HTML 本文（unsubscribe リンク・開封ピクセル埋め込み済を期待）
     * @param adEmailDeliveryId    {@code ad_email_deliveries.id}（enqueue の source_event_id として使用。双方向トレース用）
     * @return {@link AdMailSendResult}（recipient と outboxId を含む）
     */
    @Transactional
    public AdMailSendResult sendSystemAdMail(
            Long advertiserAccountId,
            Long userId,
            String recipientEmail,
            String subject,
            String bodyHtml,
            UUID adEmailDeliveryId) {
        if (advertiserAccountId == null || userId == null
                || recipientEmail == null || recipientEmail.isBlank()
                || subject == null || bodyHtml == null) {
            throw new IllegalArgumentException(
                    "advertiserAccountId, userId, recipientEmail, subject, bodyHtml は必須です");
        }

        // 件名プレフィックス強制（既に [PR] 付与済なら二重付与しない）
        String enforcedSubject = subject.startsWith(AD_SUBJECT_PREFIX)
                ? subject
                : AD_SUBJECT_PREFIX + subject;

        // 1) direct_mail_logs を 1 件作成
        DirectMailLogEntity log = DirectMailLogEntity.builder()
                .scopeType(AD_SCOPE_TYPE)
                .scopeId(advertiserAccountId)
                .senderId(0L) // システム送信。F09.17 ε-C で SYSTEM USER 化検討。
                .senderType(SENDER_TYPE_SYSTEM_AD)
                .subject(enforcedSubject)
                .bodyMarkdown(bodyHtml) // Markdown 変換は ε-B 範囲外。HTML をそのまま保存。
                .bodyHtml(bodyHtml)
                .recipientType("ROLE")
                .recipientFilter(null)
                .estimatedRecipients(1)
                .build();
        DirectMailLogEntity savedLog = mailLogRepository.save(log);

        // 2) direct_mail_recipients を 1 件作成
        DirectMailRecipientEntity recipient = DirectMailRecipientEntity.builder()
                .mailLogId(savedLog.getId())
                .userId(userId)
                .email(recipientEmail)
                .build();

        // 3) SES 送信 — EmailOutboxService 経由（outbox + リトライ保証）
        // F09.18 Phase 18-f: source_domain="advertising"、source_event_id=ad_email_delivery.id で双方向トレース
        UUID outboxId = emailOutboxService.enqueue(new EmailOutboxRequest(
                "DIRECT_MAIL_AD",
                "ja",
                recipientEmail,
                java.util.Map.of("subject", enforcedSubject, "body", bodyHtml),
                "advertising",                                                    // "directmail" から変更
                adEmailDeliveryId != null ? adEmailDeliveryId.toString() : null, // delivery UUID を使用
                null,
                userId,
                advertiserAccountId
        ));

        // 4) recipient.status=SENT を記録。messageId は EmailService 内でログのみで取得不可のため
        //    本メソッドからは sesMessageId を空のまま SENT マークする。SES Webhook で後追い更新。
        recipient.markSent(null);
        DirectMailRecipientEntity savedRecipient = recipientRepository.save(recipient);

        // 5) log を SENT に
        savedLog.markSent(1, 1);
        mailLogRepository.save(savedLog);

        return new AdMailSendResult(savedRecipient, outboxId);
    }

    /**
     * メールを作成する（下書き保存）。
     *
     * <p>変更系のため送信者（操作者）はスコープの ADMIN 以上であること。</p>
     */
    @Transactional
    public DirectMailResponse createMail(String scopeType, Long scopeId, Long senderId,
                                          CreateDirectMailRequest request) {
        accessControlService.checkAdminOrAbove(senderId, scopeId, scopeType);
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
     * メール一覧を取得する。閲覧系のため操作者はスコープのメンバーであること。
     */
    public PagedResponse<DirectMailResponse> listMails(String scopeType, Long scopeId, Long actorUserId,
                                                        Pageable pageable) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType);
        Page<DirectMailLogEntity> page = mailLogRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId, pageable);
        List<DirectMailResponse> content = directMailMapper.toMailResponseList(page.getContent());
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(content, meta);
    }

    /**
     * メール詳細を取得する。閲覧系のため操作者はスコープのメンバーであること。
     * path スコープと不一致の mailId は 404（存在秘匿）。
     */
    public DirectMailResponse getMail(String scopeType, Long scopeId, Long actorUserId, Long mailId) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType);
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);
        return directMailMapper.toMailResponse(entity);
    }

    /**
     * メールを編集する（下書きのみ）。変更系のため操作者はスコープの ADMIN 以上であること。
     */
    @Transactional
    public DirectMailResponse updateMail(String scopeType, Long scopeId, Long actorUserId, Long mailId,
                                          UpdateDirectMailRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
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
     *
     * <p>F09.13: 組織スコープのダイレクトメールは課金対象。
     * {@code estimatedRecipients} が設定されている場合はその値でクレジット消費する。</p>
     *
     * <p>認可: なりすまし一斉送信の根治のため、操作者はスコープの ADMIN 以上であること。</p>
     */
    @Transactional
    public DirectMailResponse sendMail(String scopeType, Long scopeId, Long actorUserId, Long mailId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        DirectMailLogEntity entity = findMailOrThrow(scopeType, scopeId, mailId);
        if (!"DRAFT".equals(entity.getStatus()) && !"SCHEDULED".equals(entity.getStatus())) {
            throw new BusinessException(DirectMailErrorCode.ALREADY_SENT);
        }

        // F09.13: 組織スコープのダイレクトメールはクレジット消費（送信前ゲート）
        if ("ORGANIZATION".equals(scopeType) && entity.getEstimatedRecipients() != null
                && entity.getEstimatedRecipients() > 0) {
            notificationCreditService.consume(scopeId, entity.getEstimatedRecipients(),
                    NotificationSourceType.DIRECT_MAIL);
        }

        entity.markSending();
        DirectMailLogEntity saved = mailLogRepository.save(entity);
        log.info("ダイレクトメール送信開始: mailId={}", mailId);

        eventPublisher.publish(new DirectMailSendEvent(saved.getId(), scopeType, scopeId));
        return directMailMapper.toMailResponse(saved);
    }

    /**
     * メールを予約送信する。変更系のため操作者はスコープの ADMIN 以上であること。
     */
    @Transactional
    public DirectMailResponse scheduleMail(String scopeType, Long scopeId, Long actorUserId, Long mailId,
                                            ScheduleMailRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
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
     * 送信をキャンセルする。変更系のため操作者はスコープの ADMIN 以上であること。
     */
    @Transactional
    public DirectMailResponse cancelMail(String scopeType, Long scopeId, Long actorUserId, Long mailId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
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
     *
     * <p>認可: 受信者のメールアドレス（PII）を含むため、閲覧系だが ADMIN 以上に限定する
     * （台帳 findings「受信者一覧 PII露出」の根治）。</p>
     */
    public PagedResponse<DirectMailRecipientResponse> listRecipients(String scopeType, Long scopeId,
                                                                      Long actorUserId, Long mailId,
                                                                      Pageable pageable) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        findMailOrThrow(scopeType, scopeId, mailId);
        Page<DirectMailRecipientEntity> page = recipientRepository.findByMailLogId(mailId, pageable);
        List<DirectMailRecipientResponse> content = directMailMapper.toRecipientResponseList(page.getContent());
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(content, meta);
    }

    /**
     * 送信統計を取得する。閲覧系のため操作者はスコープのメンバーであること（集計値のみ・PIIなし）。
     */
    public DirectMailStatsResponse getStats(String scopeType, Long scopeId, Long actorUserId, Long mailId) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType);
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
     *
     * <p>純関数（Markdown→HTML）だがスコープ付き公開入口のため、操作者はスコープのメンバーであること
     * （認可皆無の入口を残さない）。</p>
     */
    public PreviewMailResponse preview(String scopeType, Long scopeId, Long actorUserId,
                                        PreviewMailRequest request) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType);
        String html = MarkdownConverter.toHtml(request.getBodyMarkdown());
        return new PreviewMailResponse(html);
    }

    /**
     * 配信対象数を見積もる。
     * recipientType: ALL（全メンバー）, ROLE（ロール指定）
     * recipientFilter: ROLE の場合 {"role":"MEMBER"} 等のJSON
     *
     * <p>認可: ロール別メンバー数はスコープ内部のインテリジェンスであり、メール作成フロー専用の
     * 補助 EP のため ADMIN 以上に限定する。</p>
     */
    public EstimateRecipientsResponse estimateRecipients(String scopeType, Long scopeId, Long actorUserId,
                                                          EstimateRecipientsRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        int estimated = resolveRecipientCount(scopeType, scopeId,
                request.getRecipientType(), request.getRecipientFilter());
        log.info("配信対象数見積: scopeType={}, scopeId={}, type={}, estimated={}",
                scopeType, scopeId, request.getRecipientType(), estimated);
        return new EstimateRecipientsResponse(estimated);
    }

    /**
     * recipientType/recipientFilter に基づいて対象メンバー数を算出する。
     */
    int resolveRecipientCount(String scopeType, Long scopeId,
                               String recipientType, String recipientFilter) {
        if ("ROLE".equals(recipientType) && recipientFilter != null) {
            String roleName = extractRoleFromFilter(recipientFilter);
            if (roleName != null) {
                return userRoleRepository.countMembersByScopeAndRole(scopeType, scopeId, roleName);
            }
        }
        return userRoleRepository.countMembersByScope(scopeType, scopeId);
    }

    /**
     * recipientFilter JSON から role 値を抽出する。
     * 期待形式: {"role":"MEMBER"}
     */
    private String extractRoleFromFilter(String recipientFilter) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(recipientFilter);
            com.fasterxml.jackson.databind.JsonNode roleNode = node.get("role");
            return roleNode != null ? roleNode.asText() : null;
        } catch (Exception e) {
            log.warn("recipientFilter のパース失敗: {}", recipientFilter, e);
            return null;
        }
    }

    private DirectMailLogEntity findMailOrThrow(String scopeType, Long scopeId, Long mailId) {
        return mailLogRepository.findByIdAndScopeTypeAndScopeId(mailId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(DirectMailErrorCode.MAIL_NOT_FOUND));
    }
}
