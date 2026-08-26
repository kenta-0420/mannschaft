package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.AttachmentDownloadUrlResponse;
import com.mannschaft.app.bulletin.dto.AttachmentPresignRequest;
import com.mannschaft.app.bulletin.dto.AttachmentPresignResponse;
import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.CreateAttachmentRequest;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinAttachmentRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.FileTypeValidator;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 掲示板（F05.1）添付ファイルの永続化サービス（方式 A：presigned URL）。
 *
 * <p>F05.2 回覧板の {@code CirculationService} の presign / 確定 / 削除フローを範として、
 * 既存の {@code bulletin_attachments}（BIGINT PK）テーブルを流用して実装する。新規 DDL・
 * Flyway 採番は不要（CLAUDE.md 原則 6「既存 BIGINT は変更しない」に従う）。</p>
 *
 * <h2>スコープと認可</h2>
 * <p>添付対象（スレッド/返信）から所属スレッドを逆引きし、その {@link ScopeType}
 * （ORGANIZATION / TEAM / PERSONAL / VILLAGE）に応じて認可を行う。
 * 他村・他組織のリソースを操作できないこと（IDOR 防止）を全エンドポイントで保証する。</p>
 * <ul>
 *   <li>ORG / TEAM: {@link BulletinAccessGuard} 経由で所属・ロールを実効認可する。</li>
 *   <li>VILLAGE: {@link VillageBulletinAccessService} 経由（閲覧/モデレーター）で村ロールを正準解決する。</li>
 *   <li>PERSONAL: 本人スコープ（{@code scope_id = userId}）として扱う。</li>
 * </ul>
 *
 * <h2>クォータ（F13 統合ストレージ）</h2>
 * <p>{@link StorageQuotaService} のスコープは ORG / TEAM / PERSONAL のみ。VILLAGE スコープには
 * 対応 enum が無いため、{@code ChatAttachmentService} の村ロビー添付と同様にアップロード操作者の
 * PERSONAL スコープへフォールバックして計上する。</p>
 *
 * @see com.mannschaft.app.circulation.service.CirculationService
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinAttachmentService {

    /** 1 ターゲット（スレッド/返信）あたりの添付ファイル数上限。 */
    public static final int MAX_ATTACHMENTS_PER_TARGET = 5;

    /** 1 添付ファイルあたりのサイズ上限（10MB）。 */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** presigned PUT URL の有効期限。 */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    /** ダウンロード用 presigned GET URL の有効期限（短命）。 */
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    /** {@code storage_usage_logs.reference_type} に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "bulletin_attachments";

    /**
     * 許可する MIME タイプ（掲示板は文書・画像中心）。
     * SVG は {@link FileTypeValidator#BLOCKED_CONTENT_TYPES} により禁止。
     * {@link FileTypeValidator} の定数を合成して使用する。
     */
    public static final Set<String> ALLOWED_CONTENT_TYPES;

    static {
        var merged = new java.util.HashSet<String>();
        merged.addAll(FileTypeValidator.ALLOWED_IMAGE_TYPES);
        merged.addAll(FileTypeValidator.ALLOWED_DOCUMENT_TYPES);
        merged.add("application/zip");
        ALLOWED_CONTENT_TYPES = java.util.Collections.unmodifiableSet(merged);
    }

    private final BulletinAttachmentRepository attachmentRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinReplyRepository replyRepository;
    private final BulletinMapper bulletinMapper;
    private final BulletinAccessGuard accessGuard;
    private final VillageBulletinAccessService villageBulletinAccessService;
    /** F08.7.1 連絡機能: 大会/ディビジョンスコープの閲覧・投稿認可を委譲する（クロスドメイン・原則1）。 */
    private final TournamentContactAccessService tournamentContactAccessService;
    private final StorageQuotaService storageQuotaService;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // 1. presign（アップロード URL 発行）
    // ─────────────────────────────────────────────

    /**
     * 添付ファイルアップロード用の Presigned PUT URL を発行する。
     *
     * <p>対象スレッド/返信を逆引きしてスコープを解決し、投稿者になり得る者のみ許可する
     * （= 閲覧認可 + 投稿系の所属チェック）。presign 前に MIME / サイズ / 件数を検証する。</p>
     *
     * @param req    presign リクエスト
     * @param userId 操作ユーザー ID
     * @return uploadUrl / fileKey / 有効期限
     */
    public AttachmentPresignResponse generateUploadUrl(AttachmentPresignRequest req, Long userId) {
        BulletinThreadEntity thread = resolveThread(req.targetType(), req.targetId());

        // 投稿者になり得る者か（閲覧 + 所属）を検証
        checkUploadAuthorization(thread, userId);

        // MIME ホワイトリスト
        validateContentType(req.contentType());

        // サイズ上限
        validateFileSize(req.fileSize());

        // 件数上限
        validateAttachmentCount(req.targetType(), req.targetId());

        // F13 統合クォータ
        QuotaScope qs = resolveQuotaScope(thread, userId);
        storageQuotaService.checkQuota(qs.scopeType(), qs.scopeId(), req.fileSize());

        // fileKey: bulletin/{scopeType}/{scopeId}/{targetType}/{targetId}/{uuid}
        String fileKey = buildFileKey(thread, req.targetType(), req.targetId());

        PresignedUploadResult result =
                storageService.generateUploadUrl(fileKey, req.contentType(), PRESIGN_TTL);

        log.info("掲示板添付 presign 発行: targetType={}, targetId={}, scope={}/{}, fileKey={}",
                req.targetType(), req.targetId(), thread.getScopeType(), thread.getScopeId(), fileKey);

        return new AttachmentPresignResponse(result.uploadUrl(), fileKey, result.expiresInSeconds());
    }

    // ─────────────────────────────────────────────
    // 2. 確定（メタデータ登録）
    // ─────────────────────────────────────────────

    /**
     * presign 後のアップロード完了を受けて添付メタデータを登録する。
     *
     * @param req    確定リクエスト（presign で得た fileKey を含む）
     * @param userId 操作ユーザー ID（添付の作成者として記録）
     * @return 登録された添付レスポンス
     */
    @Transactional
    public AttachmentResponse confirmAttachment(CreateAttachmentRequest req, Long userId) {
        BulletinThreadEntity thread = resolveThread(req.targetType(), req.targetId());

        // 投稿者になり得る者か
        checkUploadAuthorization(thread, userId);

        // 再検証（確定時も MIME / サイズ / 件数を確認）
        validateContentType(req.contentType());
        validateFileSize(req.fileSize());
        validateAttachmentCount(req.targetType(), req.targetId());

        BulletinAttachmentEntity attachment = BulletinAttachmentEntity.builder()
                .targetType(req.targetType())
                .targetId(req.targetId())
                .fileKey(req.fileKey())
                .originalFilename(req.originalFilename())
                .fileSize(req.fileSize())
                .contentType(req.contentType())
                .createdBy(userId)
                .build();

        BulletinAttachmentEntity saved = attachmentRepository.save(attachment);

        // F13 使用量加算
        QuotaScope qs = resolveQuotaScope(thread, userId);
        storageQuotaService.recordUpload(
                qs.scopeType(), qs.scopeId(), req.fileSize(),
                StorageFeatureType.BULLETIN, REFERENCE_TYPE, saved.getId(), userId);

        log.info("掲示板添付 確定: attachmentId={}, targetType={}, targetId={}",
                saved.getId(), req.targetType(), req.targetId());

        return bulletinMapper.toAttachmentResponse(saved);
    }

    // ─────────────────────────────────────────────
    // 3. 一覧取得
    // ─────────────────────────────────────────────

    /**
     * スレッドの添付ファイル一覧を取得する。閲覧認可を行う。
     *
     * @param threadId スレッド ID
     * @param userId   操作ユーザー ID
     * @return 添付レスポンスリスト
     */
    public List<AttachmentResponse> listThreadAttachments(Long threadId, Long userId) {
        BulletinThreadEntity thread = findThreadOrThrow(threadId);
        checkViewAuthorization(thread, userId);
        return bulletinMapper.toAttachmentResponseList(
                attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.THREAD, threadId));
    }

    /**
     * 返信の添付ファイル一覧を取得する。閲覧認可を行う。
     *
     * @param replyId 返信 ID
     * @param userId  操作ユーザー ID
     * @return 添付レスポンスリスト
     */
    public List<AttachmentResponse> listReplyAttachments(Long replyId, Long userId) {
        BulletinThreadEntity thread = resolveThread(TargetType.REPLY, replyId);
        checkViewAuthorization(thread, userId);
        return bulletinMapper.toAttachmentResponseList(
                attachmentRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.REPLY, replyId));
    }

    // ─────────────────────────────────────────────
    // 4. ダウンロード URL 発行
    // ─────────────────────────────────────────────

    /**
     * 添付ファイルの短命 presigned GET URL を発行する。生の fileKey は返さない。閲覧認可を行う。
     *
     * @param attachmentId 添付ファイル ID
     * @param userId       操作ユーザー ID
     * @return 短命ダウンロード URL
     */
    public AttachmentDownloadUrlResponse generateDownloadUrl(Long attachmentId, Long userId) {
        BulletinAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.ATTACHMENT_NOT_FOUND));

        BulletinThreadEntity thread = resolveThread(attachment.getTargetType(), attachment.getTargetId());
        checkViewAuthorization(thread, userId);

        String downloadUrl = storageService.generateDownloadUrl(attachment.getFileKey(), DOWNLOAD_TTL);

        log.info("掲示板添付 download-url 発行: attachmentId={}, userId={}", attachmentId, userId);
        return new AttachmentDownloadUrlResponse(downloadUrl, DOWNLOAD_TTL.toSeconds());
    }

    // ─────────────────────────────────────────────
    // 5. 削除
    // ─────────────────────────────────────────────

    /**
     * 添付ファイルを削除する。本人 or モデレーター/ADMIN のみ。R2 はベストエフォート削除し、
     * 使用量を減算して監査ログを発火する。
     *
     * @param attachmentId 添付ファイル ID
     * @param userId       操作ユーザー ID
     */
    @Transactional
    public void deleteAttachment(Long attachmentId, Long userId) {
        BulletinAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.ATTACHMENT_NOT_FOUND));

        BulletinThreadEntity thread = resolveThread(attachment.getTargetType(), attachment.getTargetId());

        // 本人 or モデレーター/ADMIN
        checkDeleteAuthorization(thread, attachment, userId);

        String fileKey = attachment.getFileKey();
        long size = attachment.getFileSize() != null ? attachment.getFileSize() : 0L;

        attachmentRepository.delete(attachment);

        // R2 ベストエフォート削除
        if (fileKey != null) {
            try {
                storageService.delete(fileKey);
            } catch (Exception e) {
                log.warn("R2 オブジェクト削除失敗（ベストエフォート）: fileKey={}, error={}", fileKey, e.getMessage());
            }
        }

        // F13 使用量減算（作成者の所属スコープで計上：upload 時と対称）
        QuotaScope qs = resolveQuotaScope(thread, attachment.getCreatedBy());
        if (size > 0) {
            storageQuotaService.recordDeletion(
                    qs.scopeType(), qs.scopeId(), size,
                    StorageFeatureType.BULLETIN, REFERENCE_TYPE, attachmentId, userId);
        }

        // 監査ログ
        Long teamId = thread.getScopeType() == ScopeType.TEAM ? thread.getScopeId() : null;
        Long orgId = thread.getScopeType() == ScopeType.ORGANIZATION ? thread.getScopeId() : null;
        auditLogService.record(
                AuditEventType.BULLETIN_ATTACHMENT_DELETED.name(), userId, null,
                teamId, orgId, null, null, null,
                "{\"attachmentId\":" + attachmentId
                        + ",\"targetType\":\"" + attachment.getTargetType()
                        + "\",\"targetId\":" + attachment.getTargetId()
                        + ",\"fileKey\":\"" + (fileKey == null ? "" : fileKey.replace("\"", "\\\"")) + "\"}");

        log.info("掲示板添付 削除: attachmentId={}, userId={}", attachmentId, userId);
    }

    // ─────────────────────────────────────────────
    // 内部: スコープ逆引き
    // ─────────────────────────────────────────────

    /**
     * 添付対象（スレッド/返信）から所属スレッドを逆引きする。
     */
    private BulletinThreadEntity resolveThread(TargetType targetType, Long targetId) {
        return switch (targetType) {
            case THREAD -> findThreadOrThrow(targetId);
            case REPLY -> {
                BulletinReplyEntity reply = replyRepository.findById(targetId)
                        .filter(r -> r.getDeletedAt() == null)
                        .orElseThrow(() -> new BusinessException(BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND));
                yield findThreadOrThrow(reply.getThreadId());
            }
        };
    }

    private BulletinThreadEntity findThreadOrThrow(Long threadId) {
        return threadRepository.findById(threadId)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.ATTACHMENT_TARGET_NOT_FOUND));
    }

    // ─────────────────────────────────────────────
    // 内部: 認可
    // ─────────────────────────────────────────────

    /**
     * 閲覧認可。VILLAGE は村閲覧認可、ORG/TEAM はメンバーシップ、PERSONAL は本人のみ。
     */
    private void checkViewAuthorization(BulletinThreadEntity thread, Long userId) {
        if (thread.getScopeType() == ScopeType.VILLAGE) {
            villageBulletinAccessService.checkVillageBulletinViewAccess(thread.getScopeVillageId(), userId);
        } else if (isTournamentScope(thread.getScopeType())) {
            // F08.7.1: 大会/ディビジョン連絡の添付閲覧（一覧/DL URL 発行）は canView に委譲する。
            // checkMembership は membership.domain.ScopeType に TOURNAMENT が無く 500 になるため通さない。
            tournamentContactAccessService.checkView(
                    toContactScope(thread.getScopeType()), thread.getScopeId(), ContactSpaceKind.BULLETIN, userId);
        } else if (thread.getScopeType() == ScopeType.PERSONAL) {
            checkPersonalOwner(thread, userId);
        } else {
            accessGuard.checkMembership(userId, thread.getScopeType(), thread.getScopeId());
        }
    }

    /**
     * 投稿者になり得る者か（= 閲覧 + 所属）を検証する。
     *
     * <p>VILLAGE は村メンバー閲覧認可（MEMBERS_ONLY なら非メンバーを弾く）に委ね、ORG/TEAM は
     * メンバーシップを要求する。投稿系の細かいロール（SUPPORTER 不可等）はスレッド/返信作成 API 側で
     * 既に検証済みであり、添付はそれに付随するため所属レベルで足りる。</p>
     */
    private void checkUploadAuthorization(BulletinThreadEntity thread, Long userId) {
        // F08.7.1: 大会/ディビジョン連絡への添付は投稿行為のため canPost（代表/副代表 or 主催者）を要求する。
        // 一般メンバー（canView のみ）が添付投稿で権限昇格しないよう、閲覧より厳しくする。
        if (isTournamentScope(thread.getScopeType())) {
            tournamentContactAccessService.checkPost(
                    toContactScope(thread.getScopeType()), thread.getScopeId(), userId);
            return;
        }
        checkViewAuthorization(thread, userId);
    }

    /**
     * 削除認可。本人 or モデレーター/ADMIN（VILLAGE は村モデレーター、ORG/TEAM は ADMIN/DEPUTY、
     * PERSONAL は本人のみ）。
     */
    private void checkDeleteAuthorization(BulletinThreadEntity thread,
                                          BulletinAttachmentEntity attachment, Long userId) {
        // 本人は常に許可
        if (userId != null && userId.equals(attachment.getCreatedBy())) {
            return;
        }
        switch (thread.getScopeType()) {
            case VILLAGE -> villageBulletinAccessService
                    .checkVillageBulletinModerator(thread.getScopeVillageId(), userId);
            // F08.7.1: 大会/ディビジョン連絡の他者添付削除はモデレーション相当＝canPost（代表/主催者）を要求する。
            case TOURNAMENT, TOURNAMENT_DIVISION -> tournamentContactAccessService.checkPost(
                    toContactScope(thread.getScopeType()), thread.getScopeId(), userId);
            case PERSONAL -> checkPersonalOwner(thread, userId); // 本人以外は 403
            default -> accessGuard.checkOwnerOrAdmin(
                    userId, attachment.getCreatedBy(), thread.getScopeType(), thread.getScopeId());
        }
    }

    /** 添付対象スレッドが大会/ディビジョン連絡スペースか。 */
    private static boolean isTournamentScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT || scopeType == ScopeType.TOURNAMENT_DIVISION;
    }

    /** bulletin {@link ScopeType} を連絡スペースの {@link ContactSpaceScopeType} に変換する。 */
    private static ContactSpaceScopeType toContactScope(ScopeType scopeType) {
        return scopeType == ScopeType.TOURNAMENT
                ? ContactSpaceScopeType.TOURNAMENT
                : ContactSpaceScopeType.TOURNAMENT_DIVISION;
    }

    /**
     * PERSONAL スコープの本人チェック（scope_id == userId）。本人以外は 403。
     */
    private void checkPersonalOwner(BulletinThreadEntity thread, Long userId) {
        if (userId == null || !userId.equals(thread.getScopeId())) {
            throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        }
    }

    // ─────────────────────────────────────────────
    // 内部: 検証
    // ─────────────────────────────────────────────

    private void validateContentType(String contentType) {
        // ブロックリスト優先（危険な MIME タイプを明示排除）
        if (FileTypeValidator.isBlocked(contentType)) {
            throw new BusinessException(BulletinErrorCode.ATTACHMENT_INVALID_CONTENT_TYPE);
        }
        // ホワイトリスト検証
        if (!FileTypeValidator.isAllowed(contentType, ALLOWED_CONTENT_TYPES)) {
            throw new BusinessException(BulletinErrorCode.ATTACHMENT_INVALID_CONTENT_TYPE);
        }
    }

    private void validateFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0 || fileSize > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(BulletinErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }
    }

    private void validateAttachmentCount(TargetType targetType, Long targetId) {
        long count = attachmentRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(targetType, targetId).size();
        if (count >= MAX_ATTACHMENTS_PER_TARGET) {
            throw new BusinessException(BulletinErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }
    }

    // ─────────────────────────────────────────────
    // 内部: fileKey / quota スコープ
    // ─────────────────────────────────────────────

    private String buildFileKey(BulletinThreadEntity thread, TargetType targetType, Long targetId) {
        String scopeType = thread.getScopeType().name();
        // VILLAGE はスコープ ID として村 UUID を採用（scope_id は 0 のため一意性が弱い）
        String scopeId = thread.getScopeType() == ScopeType.VILLAGE
                ? String.valueOf(thread.getScopeVillageId())
                : String.valueOf(thread.getScopeId());
        return "bulletin/" + scopeType + "/" + scopeId
                + "/" + targetType.name() + "/" + targetId + "/" + UUID.randomUUID();
    }

    /**
     * F13 クォータスコープを解決する。VILLAGE は対応 enum が無いため操作者の PERSONAL に計上する
     * （{@code ChatAttachmentService} の村ロビー添付と同方針）。
     */
    private QuotaScope resolveQuotaScope(BulletinThreadEntity thread, Long userId) {
        return switch (thread.getScopeType()) {
            case ORGANIZATION -> new QuotaScope(StorageScopeType.ORGANIZATION, thread.getScopeId());
            case TEAM -> new QuotaScope(StorageScopeType.TEAM, thread.getScopeId());
            case PERSONAL -> new QuotaScope(StorageScopeType.PERSONAL, thread.getScopeId());
            // VILLAGE / 大会連絡スペース（TOURNAMENT / TOURNAMENT_DIVISION）は対応する
            // StorageScopeType が無いため、操作者の PERSONAL クォータに計上する
            // （ChatAttachmentService の村ロビー添付と同方針）。
            case VILLAGE, TOURNAMENT, TOURNAMENT_DIVISION -> new QuotaScope(StorageScopeType.PERSONAL, userId);
        };
    }

    /** 解決された F13 クォータスコープ。 */
    private record QuotaScope(StorageScopeType scopeType, Long scopeId) {}
}
