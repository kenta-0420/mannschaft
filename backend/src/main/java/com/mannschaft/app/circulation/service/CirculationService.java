package com.mannschaft.app.circulation.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.CirculationMapper;
import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.CirculationPriority;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.StampDisplayStyle;
import com.mannschaft.app.circulation.dto.AddRecipientsRequest;
import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CirculationAttachmentPresignRequest;
import com.mannschaft.app.circulation.dto.CirculationAttachmentPresignResponse;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.dto.CreateAttachmentRequest;
import com.mannschaft.app.circulation.dto.CreateDocumentRequest;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatsResponse;
import com.mannschaft.app.circulation.dto.DocumentStatusResponse;
import com.mannschaft.app.circulation.dto.ForceCompleteBatchResponse;
import com.mannschaft.app.circulation.dto.RecipientEntry;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.RecipientStatusEntry;
import com.mannschaft.app.circulation.dto.RemindResponse;
import com.mannschaft.app.circulation.dto.UpdateDocumentRequest;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.UserRepository.MemberSummary;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.event.CirculationDocumentDeletedEvent;
import com.mannschaft.app.circulation.repository.CirculationAttachmentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 回覧板サービス。文書CRUD・受信者管理・添付ファイル管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CirculationService {

    /** F13 Phase 5-a: presigned URL の有効期限。 */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;
    private final CirculationAttachmentRepository attachmentRepository;
    private final CirculationMapper circulationMapper;

    /**
     * F00 Phase C 試験的置換 — 単発文書取得時の可視性ガード用。
     * Bean 不在のテスト構成では {@code null} 注入され、ガードはスキップされる。
     */
    private final ContentVisibilityChecker contentVisibilityChecker;

    /** F13 Phase 5-a: R2 presigned URL 発行 / オブジェクト削除に使用。 */
    private final R2StorageService r2StorageService;

    /**
     * F09.14 Phase 4-C: 回覧文書削除イベントを発行するためのパブリッシャー。
     * 購読側（{@code DisclosureCirculationCleanupHandler} 等）が
     * クロスドメイン参照のクリーンアップを行う。
     */
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Phase 11 第三陣 3-A: 受信者表示名解決・手動リマインド・複製で使用。
     * Bean 不在のテスト構成（Mockito @InjectMocks 等）では null 注入される。
     */
    private final UserRepository userRepository;

    /** Phase 11 第三陣 3-A: 手動リマインド送信に使用。 */
    private final NotificationService notificationService;

    /**
     * 管理操作の per-scope 認可に使用する（2026-05-29 fixup）。
     *
     * <p>本アプリは {@code @EnableMethodSecurity} が未有効のため、Controller の
     * {@code @PreAuthorize("hasRole('ADMIN')")} は実機では強制力を持たない（将来の method-security
     * 有効化に備えた宣言に留まる）。さらに JWT には {@code MEMBER} しか乗らず、ADMIN/DEPUTY_ADMIN は
     * {@code user_roles} にスコープ別保持されるため {@code hasRole} では per-scope 判定にならない。
     * そこで強制完了・一括強制完了・手動リマインド・複製・押印状況閲覧の各管理操作で、処理本体の前に
     * {@link AccessControlService} による per-scope 認可（当該文書のスコープの ADMIN/DEPUTY_ADMIN、
     * または SYSTEM_ADMIN）を実施し、他団体の回覧文書への管理操作を遮断する。</p>
     */
    private final AccessControlService accessControlService;

    /**
     * Phase 11 第三陣 3-A/3-B: 監査ログサービス。
     * - 3-A: 強制完了・一括強制完了の監査ログ書き込み
     * - 3-B: 添付削除等の監査ログ書き込み（null 注入時はログ記録をスキップ）
     */
    @Autowired(required = false)
    private AuditLogService auditLogService;

    /**
     * 文書一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（null の場合は全件）
     * @param pageable  ページング情報
     * @return 文書レスポンスのページ
     */
    public Page<DocumentResponse> listDocuments(String scopeType, Long scopeId, String status, Pageable pageable) {
        Page<CirculationDocumentEntity> page;
        if (status != null) {
            CirculationStatus circulationStatus = CirculationStatus.valueOf(status);
            page = documentRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, circulationStatus, pageable);
        } else {
            page = documentRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }

        // 作成者の表示名を充填する（per-page ≤ 20 件）。
        // createdBy id の重複を避けるため distinct な id → displayName の Map を 1 パスで構築する。
        Page<DocumentResponse> dtoPage = page.map(circulationMapper::toDocumentResponse);
        if (userRepository != null) {
            Map<Long, String> displayNameMap = new HashMap<>();
            for (DocumentResponse dto : dtoPage.getContent()) {
                Long createdBy = dto.getCreatedBy();
                if (createdBy != null && !displayNameMap.containsKey(createdBy)) {
                    String name = userRepository.findMemberSummaryById(createdBy)
                            .map(MemberSummary::getDisplayName)
                            .orElse(null);
                    displayNameMap.put(createdBy, name);
                }
            }
            return dtoPage.map(dto -> dto.toBuilder()
                    .createdByName(displayNameMap.get(dto.getCreatedBy()))
                    .build());
        }
        return dtoPage;
    }

    /**
     * F22.1 第二波: 指定スコープで当該ユーザーが「未確認（未スタンプ・PENDING）」の回覧文書を取得する。
     *
     * <p>横スワイプ・ダッシュボードの統合「要対応」集計（{@code ScopeActionRequiredFacade}）から
     * 呼ばれる読み取り専用メソッド。<b>per-scope 認可をこのメソッド内で必ず通す</b>
     * （{@link AccessControlService#checkMembership}）。非所属ユーザーは
     * {@code COMMON_002} で弾かれる（集計バイパス禁止・02 §3.4）。</p>
     *
     * <p>未確認件数のカウントとアイテム取得を一度の SQL（JOIN）で行い N+1 を回避する。
     * アイテムは作成日時の降順で {@code limit} 件に絞る。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @param userId    閲覧ユーザー ID
     * @param limit     直近アイテムの最大件数
     * @return 未確認文書（全件・カウント用）と limit 件のアイテム
     */
    public UnconfirmedCirculations getUnconfirmedForUserInScope(
            String scopeType, Long scopeId, Long userId, int limit) {
        if (accessControlService != null) {
            accessControlService.checkMembership(userId, scopeId, scopeType);
        }
        List<CirculationDocumentEntity> all =
                recipientRepository.findUnconfirmedDocumentsForUserInScope(scopeType, scopeId, userId);
        List<CirculationDocumentEntity> items = all.size() > limit ? all.subList(0, limit) : all;
        return new UnconfirmedCirculations(all.size(), List.copyOf(items));
    }

    /**
     * F22.1 第二波: 未確認回覧文書の集計結果（件数 + 直近アイテム）。
     *
     * @param unconfirmedCount 未確認の総件数
     * @param items            直近アイテム（limit 件）
     */
    public record UnconfirmedCirculations(
            long unconfirmedCount,
            List<CirculationDocumentEntity> items) {
    }

    /**
     * 文書詳細を取得する。
     *
     * <p>F00 Phase C 試験的置換 (2026-05-04 / §12.3 工程 4): 既存のスコープ照合に加えて
     * {@link ContentVisibilityChecker#assertCanView} で {@link ReferenceType#CIRCULATION_DOCUMENT}
     * の可視性ガードを行う。配信先 ACL に登録されていない閲覧者は
     * {@code VISIBILITY_001 / VISIBILITY_004} で拒否される（{@link CirculationDocumentVisibilityResolver}
     * 案 A）。Bean 不在のテスト構成 (Mockito {@code @InjectMocks}) では
     * {@code contentVisibilityChecker} が {@code null} 注入されガードはスキップされる。</p>
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @return 文書レスポンス
     */
    public DocumentResponse getDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);
        if (contentVisibilityChecker != null) {
            contentVisibilityChecker.assertCanView(
                    ReferenceType.CIRCULATION_DOCUMENT,
                    entity.getId(),
                    SecurityUtils.getCurrentUserIdOrNull());
        }
        return enrichCreatedByName(circulationMapper.toDocumentResponse(entity));
    }

    /**
     * DocumentResponse に作成者の表示名（createdByName）を充填する。
     *
     * <p>{@code userRepository} が null のテスト構成ではそのまま返す（既存の防御パターン）。
     * 解決できない場合は createdByName は null のまま。</p>
     *
     * @param dto 充填対象の DTO
     * @return createdByName を充填した DTO（userRepository が null の場合は引数のまま）
     */
    private DocumentResponse enrichCreatedByName(DocumentResponse dto) {
        if (userRepository == null || dto.getCreatedBy() == null) {
            return dto;
        }
        String name = userRepository.findMemberSummaryById(dto.getCreatedBy())
                .map(MemberSummary::getDisplayName)
                .orElse(null);
        return dto.toBuilder().createdByName(name).build();
    }

    /**
     * 文書を作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ID
     * @param request   作成リクエスト
     * @return 作成された文書レスポンス
     */
    @Transactional
    public DocumentResponse createDocument(String scopeType, Long scopeId, Long userId,
                                           CreateDocumentRequest request) {
        CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .createdBy(userId)
                .title(request.getTitle())
                .body(request.getBody())
                .circulationMode(request.getCirculationMode() != null
                        ? CirculationMode.valueOf(request.getCirculationMode())
                        : CirculationMode.SIMULTANEOUS)
                .priority(request.getPriority() != null
                        ? CirculationPriority.valueOf(request.getPriority())
                        : CirculationPriority.NORMAL)
                .dueDate(request.getDueDate())
                .reminderEnabled(request.getReminderEnabled() != null ? request.getReminderEnabled() : false)
                .reminderIntervalHours(request.getReminderIntervalHours() != null
                        ? request.getReminderIntervalHours() : (short) 24)
                .stampDisplayStyle(request.getStampDisplayStyle() != null
                        ? StampDisplayStyle.valueOf(request.getStampDisplayStyle())
                        : StampDisplayStyle.STANDARD)
                .build();

        CirculationDocumentEntity saved = documentRepository.save(entity);

        addRecipientsInternal(saved, request.getRecipients());
        saved.updateRecipientCount(request.getRecipients().size());
        saved = documentRepository.save(saved);

        log.info("回覧文書作成: scopeType={}, scopeId={}, documentId={}", scopeType, scopeId, saved.getId());
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書を更新する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @param request    更新リクエスト
     * @return 更新された文書レスポンス
     */
    @Transactional
    public DocumentResponse updateDocument(String scopeType, Long scopeId, Long documentId,
                                           UpdateDocumentRequest request) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);

        if (!entity.isEditable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        if (request.getTitle() != null || request.getBody() != null) {
            entity.updateContent(
                    request.getTitle() != null ? request.getTitle() : entity.getTitle(),
                    request.getBody() != null ? request.getBody() : entity.getBody());
        }

        entity.updateSettings(
                request.getPriority() != null
                        ? CirculationPriority.valueOf(request.getPriority()) : entity.getPriority(),
                request.getDueDate() != null ? request.getDueDate() : entity.getDueDate(),
                request.getReminderEnabled() != null ? request.getReminderEnabled() : entity.getReminderEnabled(),
                request.getReminderIntervalHours() != null
                        ? request.getReminderIntervalHours() : entity.getReminderIntervalHours(),
                request.getStampDisplayStyle() != null
                        ? StampDisplayStyle.valueOf(request.getStampDisplayStyle()) : entity.getStampDisplayStyle());

        CirculationDocumentEntity saved = documentRepository.save(entity);
        log.info("回覧文書更新: documentId={}", documentId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書を公開する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @return 更新された文書レスポンス
     */
    @Transactional
    public DocumentResponse activateDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);

        if (!entity.isEditable()) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        long recipientCount = recipientRepository.countByDocumentId(documentId);
        if (recipientCount == 0) {
            throw new BusinessException(CirculationErrorCode.EMPTY_RECIPIENTS);
        }

        entity.activate();

        if (entity.getCirculationMode() == CirculationMode.SEQUENTIAL) {
            // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
            // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
            entity.updateSequentialCount((int) recipientCount);
        }

        CirculationDocumentEntity saved = documentRepository.save(entity);
        log.info("回覧文書公開: documentId={}", documentId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書をキャンセルする。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @return 更新された文書レスポンス
     */
    @Transactional
    public DocumentResponse cancelDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);
        entity.cancel();
        CirculationDocumentEntity saved = documentRepository.save(entity);
        log.info("回覧文書キャンセル: documentId={}", documentId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書を論理削除する。
     *
     * <p>F09.14 Phase 4-C: 削除時に {@link CirculationDocumentDeletedEvent} を発行し、
     * F09.14 等の購読側で参照を自動クリーンアップする（クロスドメイン FK 撤去後の
     * アプリケーション層整合性保証）。イベントは {@code @TransactionalEventListener}
     * の {@code AFTER_COMMIT} フェーズで非同期処理されるため、本トランザクション失敗時は
     * 購読側処理も発火しない。</p>
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     */
    @Transactional
    public void deleteDocument(String scopeType, Long scopeId, Long documentId) {
        CirculationDocumentEntity entity = findDocumentOrThrow(scopeType, scopeId, documentId);
        entity.softDelete();
        documentRepository.save(entity);
        applicationEventPublisher.publishEvent(new CirculationDocumentDeletedEvent(documentId));
        log.info("回覧文書削除: documentId={}", documentId);
    }

    /**
     * 受信者一覧を取得する。
     *
     * @param documentId 文書ID
     * @return 受信者レスポンスリスト
     */
    public List<RecipientResponse> listRecipients(Long documentId) {
        List<CirculationRecipientEntity> recipients =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(documentId);
        return circulationMapper.toRecipientResponseList(recipients);
    }

    /**
     * 受信者を追加する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @param request    追加リクエスト
     * @return 受信者レスポンスリスト
     */
    @Transactional
    public List<RecipientResponse> addRecipients(String scopeType, Long scopeId, Long documentId,
                                                 AddRecipientsRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);

        addRecipientsInternal(document, request.getRecipients());

        long count = recipientRepository.countByDocumentId(documentId);
        document.updateRecipientCount((int) count);
        documentRepository.save(document);

        List<CirculationRecipientEntity> all =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(documentId);
        log.info("受信者追加: documentId={}, 追加数={}", documentId, request.getRecipients().size());
        return circulationMapper.toRecipientResponseList(all);
    }

    /**
     * 受信者を削除する。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param documentId  文書ID
     * @param recipientId 受信者ID
     */
    @Transactional
    public void removeRecipient(String scopeType, Long scopeId, Long documentId, Long recipientId) {
        findDocumentOrThrow(scopeType, scopeId, documentId);

        CirculationRecipientEntity recipient = recipientRepository.findById(recipientId)
                .filter(r -> r.getDocumentId().equals(documentId))
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.RECIPIENT_NOT_FOUND));

        recipientRepository.delete(recipient);

        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);
        long count = recipientRepository.countByDocumentId(documentId);
        document.updateRecipientCount((int) count);
        documentRepository.save(document);

        log.info("受信者削除: documentId={}, recipientId={}", documentId, recipientId);
    }

    /**
     * F13 Phase 5-a: 回覧板添付ファイルのアップロード用 Presigned URL を発行する。
     *
     * <p>新統一パス命名規則 {@code circulation/{scopeType}/{scopeId}/{documentId}/{uuid}}
     * に従った fileKey をサーバー側で生成する。クライアントは返却された {@code uploadUrl} を使って
     * R2 に直接 PUT し、完了後に {@code fileKey} を {@code addAttachment} API に渡す。</p>
     *
     * @param documentId 文書 ID
     * @param req        presign リクエスト
     * @return presign レスポンス（uploadUrl / fileKey / expiresInSeconds）
     */
    @Transactional(readOnly = true)
    public CirculationAttachmentPresignResponse presignAttachmentUpload(
            Long documentId, CirculationAttachmentPresignRequest req) {

        // 1. ドキュメント取得（documentId のみで解決、scopeType/scopeId をエンティティから取得）
        CirculationDocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));

        // 2. スコープ情報の取得
        String scopeType = document.getScopeType(); // TEAM / ORGANIZATION / PERSONAL
        Long scopeId = document.getScopeId();

        // 3. fileKey 生成: circulation/{scopeType}/{scopeId}/{documentId}/{uuid}
        String fileKey = "circulation/" + scopeType + "/" + scopeId + "/" + documentId + "/" + UUID.randomUUID();

        // 4. presigned URL 発行
        PresignedUploadResult result = r2StorageService.generateUploadUrl(
                fileKey, req.contentType(), PRESIGN_TTL);

        log.info("回覧板添付 presign-upload 発行: documentId={}, scope={}/{}, fileKey={}",
                documentId, scopeType, scopeId, fileKey);

        return new CirculationAttachmentPresignResponse(result.uploadUrl(), fileKey, result.expiresInSeconds());
    }

    /**
     * 添付ファイル一覧を取得する。
     *
     * @param documentId 文書ID
     * @return 添付ファイルレスポンスリスト
     */
    public List<AttachmentResponse> listAttachments(Long documentId) {
        List<CirculationAttachmentEntity> attachments =
                attachmentRepository.findByDocumentIdOrderByCreatedAtAsc(documentId);
        return circulationMapper.toAttachmentResponseList(attachments);
    }

    /**
     * 添付ファイルを追加する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param documentId 文書ID
     * @param request    添付リクエスト
     * @return 添付ファイルレスポンス
     */
    @Transactional
    public AttachmentResponse addAttachment(String scopeType, Long scopeId, Long documentId,
                                            CreateAttachmentRequest request) {
        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);

        CirculationAttachmentEntity attachment = CirculationAttachmentEntity.builder()
                .documentId(documentId)
                .fileKey(request.getFileKey())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .mimeType(request.getMimeType())
                .build();

        CirculationAttachmentEntity saved = attachmentRepository.save(attachment);
        document.incrementAttachmentCount();
        documentRepository.save(document);

        log.info("添付ファイル追加: documentId={}, attachmentId={}", documentId, saved.getId());
        return circulationMapper.toAttachmentResponse(saved);
    }

    /**
     * 添付ファイルを削除する。
     *
     * <p>F05.2 Phase 11 第三陣 3-B 拡張:
     * <ul>
     *   <li>文書が DRAFT 状態の場合のみ削除可能</li>
     *   <li>R2 オブジェクトをベストエフォートで削除（失敗時は WARN ログ）</li>
     *   <li>監査ログ {@code CIRCULATION_ATTACHMENT_DELETED} を発火</li>
     *   <li>呼び出し元の作成者本人チェックは Controller / 上位ガード側で実施</li>
     * </ul>
     * </p>
     *
     * @param scopeType    スコープ種別
     * @param scopeId      スコープID
     * @param documentId   文書ID
     * @param attachmentId 添付ファイルID
     * @param userId       操作実行ユーザーID（監査ログ用）
     */
    @Transactional
    public void removeAttachment(String scopeType, Long scopeId, Long documentId, Long attachmentId, Long userId) {
        CirculationDocumentEntity document = findDocumentOrThrow(scopeType, scopeId, documentId);

        // DRAFT 段階のみ削除可能
        if (!document.isEditable()) {
            throw new BusinessException(CirculationErrorCode.ATTACHMENT_NOT_DELETABLE);
        }

        CirculationAttachmentEntity attachment = attachmentRepository.findByIdAndDocumentId(attachmentId, documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.ATTACHMENT_NOT_FOUND));

        String fileKey = attachment.getFileKey();

        attachmentRepository.delete(attachment);
        document.decrementAttachmentCount();
        documentRepository.save(document);

        // R2 オブジェクト削除（ベストエフォート）
        if (fileKey != null && r2StorageService != null) {
            try {
                r2StorageService.delete(fileKey);
            } catch (Exception e) {
                log.warn("R2 オブジェクト削除失敗 (ベストエフォート): fileKey={}, error={}", fileKey, e.getMessage());
            }
        }

        // 監査ログ発火
        if (auditLogService != null) {
            auditLogService.record(AuditEventType.CIRCULATION_ATTACHMENT_DELETED.name(),
                    userId, null, null, null, null, null, null,
                    "{\"documentId\":" + documentId
                            + ",\"attachmentId\":" + attachmentId
                            + ",\"fileKey\":\"" + (fileKey == null ? "" : fileKey.replace("\"", "\\\"")) + "\"}");
        }

        log.info("添付ファイル削除: documentId={}, attachmentId={}, userId={}",
                documentId, attachmentId, userId);
    }

    /**
     * 旧シグネチャ互換用（テスト等で使用）。userId=null として呼び出す。
     *
     * @deprecated F05.2 Phase 11 第三陣 3-B 以降は {@link #removeAttachment(String, Long, Long, Long, Long)}
     *             を使用する。
     */
    @Deprecated
    @Transactional
    public void removeAttachment(String scopeType, Long scopeId, Long documentId, Long attachmentId) {
        removeAttachment(scopeType, scopeId, documentId, attachmentId, null);
    }

    /**
     * 自分が作成した文書をページング取得する。
     *
     * @param userId   ユーザーID
     * @param pageable ページング情報
     * @return 文書レスポンスのページ
     */
    public Page<DocumentResponse> listCreatedDocuments(Long userId, Pageable pageable) {
        Page<CirculationDocumentEntity> page =
                documentRepository.findByCreatedByOrderByCreatedAtDesc(userId, pageable);
        return page.map(circulationMapper::toDocumentResponse);
    }

    /**
     * 文書の統計情報を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 統計レスポンス
     */
    public DocumentStatsResponse getStats(String scopeType, Long scopeId) {
        long draft = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.DRAFT);
        long active = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.ACTIVE);
        long completed = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.COMPLETED);
        long cancelled = documentRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, CirculationStatus.CANCELLED);
        long total = draft + active + completed + cancelled;

        return new DocumentStatsResponse(total, draft, active, completed, cancelled);
    }

    // ─────────────────────────────────────────────
    // Phase 11 第三陣 3-A: 管理者向け小機能群
    // ─────────────────────────────────────────────

    /**
     * 文書を強制完了する（Phase 11 第三陣 3-A）。
     *
     * <p>全受信者が未押印でも、管理者判断で {@code COMPLETED} 扱いとする。
     * 対象は {@code DRAFT} 以外（{@code IN_PROGRESS / ACTIVE} 想定）に限定し、
     * 既に {@code COMPLETED / CANCELLED} の場合は {@code INVALID_DOCUMENT_STATUS} を投げる。
     * 監査ログイベント {@code CIRCULATION_FORCE_COMPLETED} を非同期発火する。</p>
     *
     * @param documentId 文書 ID
     * @param actorId    操作者ユーザー ID
     * @return 強制完了後の文書レスポンス
     */
    @Transactional
    public DocumentResponse forceCompleteDocument(Long documentId, Long actorId) {
        CirculationDocumentEntity entity = findDocumentById(documentId);
        checkScopeAdminAccess(entity, actorId);

        if (entity.getStatus() == CirculationStatus.COMPLETED
                || entity.getStatus() == CirculationStatus.CANCELLED
                || entity.getStatus() == CirculationStatus.DRAFT) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        entity.complete();
        CirculationDocumentEntity saved = documentRepository.save(entity);

        if (auditLogService != null) {
            auditLogService.record(
                    "CIRCULATION_FORCE_COMPLETED", actorId, null,
                    "TEAM".equals(entity.getScopeType()) ? entity.getScopeId() : null,
                    "ORGANIZATION".equals(entity.getScopeType()) ? entity.getScopeId() : null,
                    null, null, null,
                    "{\"documentId\":" + documentId + "}");
        }
        log.info("回覧文書強制完了: documentId={}, actorId={}", documentId, actorId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書を一括強制完了する（Phase 11 第三陣 3-A）。
     *
     * <p>個別の {@link #forceCompleteDocument} を順次呼び出し、各文書ごとに監査ログを発火する。
     * 失敗した文書はスキップしてレスポンスに記録する（部分成功を許容）。
     * 最大件数は呼び出し側（Controller）の Bean Validation で 20 件に制限される。</p>
     *
     * @param documentIds 文書 ID リスト
     * @param actorId     操作者ユーザー ID
     * @return 成否別レスポンス
     */
    @Transactional
    public ForceCompleteBatchResponse forceCompleteBatch(List<Long> documentIds, Long actorId) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BusinessException(CirculationErrorCode.EMPTY_BATCH);
        }
        if (documentIds.size() > 20) {
            throw new BusinessException(CirculationErrorCode.BATCH_SIZE_EXCEEDED);
        }

        List<Long> succeeded = new ArrayList<>();
        List<ForceCompleteBatchResponse.FailureEntry> failed = new ArrayList<>();

        for (Long documentId : documentIds) {
            try {
                forceCompleteDocument(documentId, actorId);
                succeeded.add(documentId);
            } catch (BusinessException ex) {
                failed.add(new ForceCompleteBatchResponse.FailureEntry(
                        documentId, ex.getErrorCode().getCode(), ex.getErrorCode().getMessage()));
            }
        }
        log.info("回覧文書一括強制完了: 成功={}, 失敗={}, actorId={}", succeeded.size(), failed.size(), actorId);
        return new ForceCompleteBatchResponse(succeeded, failed);
    }

    /**
     * 文書の未押印受信者に手動リマインドを送信する（Phase 11 第三陣 3-A）。
     *
     * <p>{@code IN_PROGRESS / ACTIVE} ステータスの文書のみ対象。
     * {@code PENDING} ステータスの受信者全員に {@code CIRCULATION_REMINDER} 通知を作成する。</p>
     *
     * @param documentId 文書 ID
     * @param actorId    操作者ユーザー ID
     * @return 送信結果
     */
    @Transactional
    public RemindResponse remindDocument(Long documentId, Long actorId) {
        CirculationDocumentEntity entity = findDocumentById(documentId);
        checkScopeAdminAccess(entity, actorId);

        if (entity.getStatus() != CirculationStatus.ACTIVE) {
            throw new BusinessException(CirculationErrorCode.INVALID_DOCUMENT_STATUS);
        }

        List<CirculationRecipientEntity> pendings =
                recipientRepository.findByDocumentIdAndStatusOrderBySortOrderAsc(documentId, RecipientStatus.PENDING);

        int remindedCount = 0;
        if (notificationService != null) {
            for (CirculationRecipientEntity recipient : pendings) {
                notificationService.createNotification(
                        recipient.getUserId(),
                        "CIRCULATION_REMINDER",
                        NotificationPriority.NORMAL,
                        "回覧の未確認があります",
                        "「" + entity.getTitle() + "」の押印をお願いします。",
                        "CIRCULATION_DOCUMENT", documentId,
                        scopeTypeToNotificationScope(entity.getScopeType()),
                        entity.getScopeId(),
                        "/circulations/" + documentId,
                        actorId);
                remindedCount++;
            }
        }
        log.info("回覧手動リマインド送信: documentId={}, count={}, actorId={}", documentId, remindedCount, actorId);
        return new RemindResponse(documentId, remindedCount);
    }

    /**
     * 文書を複製する（Phase 11 第三陣 3-A）。
     *
     * <p>新規 {@code DRAFT} を作成し、受信者は元文書からコピーする（押印状態はリセット）。
     * 添付・コメントはコピーしない。
     * タイトル末尾に「(コピー)」を付与する。</p>
     *
     * @param sourceDocumentId 元文書 ID
     * @param actorId          作成者ユーザー ID
     * @return 複製後の新文書レスポンス
     */
    @Transactional
    public DocumentResponse duplicateDocument(Long sourceDocumentId, Long actorId) {
        CirculationDocumentEntity source = findDocumentById(sourceDocumentId);
        // 元文書のスコープの管理者のみ複製可能（新文書は同一スコープを継承する）
        checkScopeAdminAccess(source, actorId);

        CirculationDocumentEntity newEntity = CirculationDocumentEntity.builder()
                .scopeType(source.getScopeType())
                .scopeId(source.getScopeId())
                .createdBy(actorId)
                .title(source.getTitle() + " (コピー)")
                .body(source.getBody())
                .circulationMode(source.getCirculationMode())
                .priority(source.getPriority())
                .dueDate(source.getDueDate())
                .reminderEnabled(source.getReminderEnabled())
                .reminderIntervalHours(source.getReminderIntervalHours())
                .stampDisplayStyle(source.getStampDisplayStyle())
                .build();

        CirculationDocumentEntity saved = documentRepository.save(newEntity);

        List<CirculationRecipientEntity> sourceRecipients =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(sourceDocumentId);
        for (CirculationRecipientEntity sr : sourceRecipients) {
            CirculationRecipientEntity copy = CirculationRecipientEntity.builder()
                    .documentId(saved.getId())
                    .userId(sr.getUserId())
                    .sortOrder(sr.getSortOrder())
                    .build();
            recipientRepository.save(copy);
        }
        saved.updateRecipientCount(sourceRecipients.size());
        saved = documentRepository.save(saved);

        log.info("回覧文書複製: source={}, new={}, actorId={}", sourceDocumentId, saved.getId(), actorId);
        return circulationMapper.toDocumentResponse(saved);
    }

    /**
     * 文書の受信者ごとの押印状況一覧を取得する（Phase 11 第三陣 3-A）。
     *
     * <p>UI ブロッカー最優先案件。各受信者の {@code userId / displayName / stampStatus / stampedAt / sortOrder}
     * を返す。表示名は {@link UserRepository#findMemberSummaryById} で軽量取得する。</p>
     *
     * @param documentId 文書 ID
     * @param actorId    操作者ユーザー ID（per-scope 認可に使用）
     * @return 受信者ごとの押印状況一覧
     */
    public DocumentStatusResponse getDocumentStatus(Long documentId, Long actorId) {
        CirculationDocumentEntity entity = findDocumentById(documentId);
        checkScopeAdminAccess(entity, actorId);
        List<CirculationRecipientEntity> recipients =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(documentId);

        Map<Long, String> displayNameMap = new HashMap<>();
        if (userRepository != null) {
            for (CirculationRecipientEntity r : recipients) {
                userRepository.findMemberSummaryById(r.getUserId())
                        .ifPresent(ms -> displayNameMap.put(ms.getId(), ms.getDisplayName()));
            }
        }

        List<RecipientStatusEntry> entries = recipients.stream()
                .map(r -> new RecipientStatusEntry(
                        r.getUserId(),
                        displayNameMap.getOrDefault(r.getUserId(), null),
                        r.getStatus().name(),
                        r.getStampedAt(),
                        r.getSortOrder()))
                .toList();

        return new DocumentStatusResponse(documentId, entity.getStatus().name(), entries);
    }

    /**
     * scope_type 文字列を NotificationScopeType に変換する。
     */
    private NotificationScopeType scopeTypeToNotificationScope(String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return NotificationScopeType.TEAM;
        }
        if ("ORGANIZATION".equals(scopeType)) {
            return NotificationScopeType.ORGANIZATION;
        }
        return NotificationScopeType.PERSONAL;
    }

    /**
     * 管理操作に対する per-scope 認可を実施する（2026-05-29 fixup）。
     *
     * <p>対象文書の {@code scopeType}/{@code scopeId} を基に、現在のユーザーが当該スコープの
     * ADMIN/DEPUTY_ADMIN であることを要求する。SYSTEM_ADMIN は全スコープ許可。
     * scopeId は <b>文書エンティティ由来</b>で解決するため、URL の {@code documentId} が指す文書の
     * 実スコープと認可スコープが必ず一致する（別スコープの ID を使った IDOR を防ぐ）。</p>
     *
     * <p>{@code PERSONAL} スコープの文書には team/org の管理者という概念が無いため、
     * SYSTEM_ADMIN 以外は一律 {@code COMMON_002}（403）で遮断する。</p>
     *
     * @param document   対象文書エンティティ
     * @param actorUserId 操作者ユーザー ID（Controller では {@code SecurityUtils.getCurrentUserId()}）
     * @throws BusinessException 当該スコープの管理者でない場合（COMMON_002、403）
     */
    private void checkScopeAdminAccess(CirculationDocumentEntity document, Long actorUserId) {
        if (accessControlService.isSystemAdmin(actorUserId)) {
            return;
        }
        String scopeType = document.getScopeType();
        if (!"TEAM".equals(scopeType) && !"ORGANIZATION".equals(scopeType)) {
            // PERSONAL 等、team/org 管理者の概念が無いスコープは SYSTEM_ADMIN のみ許可
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        accessControlService.checkAdminOrAbove(actorUserId, document.getScopeId(), scopeType);
    }

    /**
     * 文書を ID のみで取得する。存在しない場合は例外をスローする。
     *
     * <p><b>F13 Phase 5-a</b>: コントローラーから動的にscopeType/scopeIdを解決するために使用する。</p>
     *
     * @param documentId 文書 ID
     * @return 文書エンティティ
     */
    public CirculationDocumentEntity findDocumentById(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * 文書を取得する。存在しない場合は例外をスローする。
     */
    private CirculationDocumentEntity findDocumentOrThrow(String scopeType, Long scopeId, Long documentId) {
        return documentRepository.findByIdAndScopeTypeAndScopeId(documentId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * 受信者を内部的に追加する。
     */
    private void addRecipientsInternal(CirculationDocumentEntity document, List<RecipientEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            RecipientEntry entry = entries.get(i);

            if (recipientRepository.existsByDocumentIdAndUserId(document.getId(), entry.getUserId())) {
                throw new BusinessException(CirculationErrorCode.DUPLICATE_RECIPIENT);
            }

            CirculationRecipientEntity recipient = CirculationRecipientEntity.builder()
                    .documentId(document.getId())
                    .userId(entry.getUserId())
                    .sortOrder(entry.getSortOrder() != null ? entry.getSortOrder() : i)
                    .build();

            recipientRepository.save(recipient);
        }
    }
}
