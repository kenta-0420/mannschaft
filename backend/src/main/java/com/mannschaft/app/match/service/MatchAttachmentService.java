package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.FileTypeValidator;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.entity.MatchAttachmentEntity;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchAttachmentRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F08.10 局面写真など match スコープ添付の永続化サービス（盤上競技・presign 方式・01 §B.7 / 03 §C.7a）。
 *
 * <p>既存添付基盤（{@code BulletinAttachmentService} の presign / 確定 / 削除フロー）を範として、match スコープ
 * （{@code match_id} 帰属確認）の添付として実装する。確立済みセキュリティ規約をそのまま踏襲する
 * （独自実装を作らない＝攻撃面を増やさない・03 §C.7a）:</p>
 * <ul>
 *   <li><b>SVG 除外</b>: {@link FileTypeValidator#BLOCKED_CONTENT_TYPES}（XSS ベクタ）を弾き、許可は画像のみ。</li>
 *   <li><b>サイズ上限 10MB</b>: 超過は 400（MATCH_033）。</li>
 *   <li><b>件数上限</b>: 1 match あたり {@link #MAX_ATTACHMENTS_PER_MATCH} 件まで（超過は 400・MATCH_034）。</li>
 *   <li><b>IDOR 逆引き</b>: 親 match をテナント取得（{@link MatchService#getMatchOrThrow}）した後、添付は
 *       {@code attachment.match_id == matchId} を必ず検証（不一致 404・子 ID 直引き禁止・二段アクセス）。</li>
 *   <li><b>key は server 採番</b>: presign 時にクライアント任意 key を信用しない（マスアサインメント防止）。</li>
 * </ul>
 *
 * <p>添付の追加（presign/確定）・削除は記録権限（{@link MatchAccessService#assertCanRecordTimeline}）を要求し、
 * 一覧・DL URL 発行は閲覧可視性（{@link MatchAccessService#canView}・F00 委譲）を Controller が検証する。
 * {@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.7
 *   / 03_permissions_and_recording_modes.md §C.7a / sports/05_shogi.md §8.2</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchAttachmentService {

    /** 1 match あたりの局面写真件数上限。 */
    public static final int MAX_ATTACHMENTS_PER_MATCH = 20;

    /** 1 添付あたりのサイズ上限（10MB・既存基盤踏襲）。 */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** presigned PUT URL の有効期限。 */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    /** ダウンロード用 presigned GET URL の有効期限（短命）。 */
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    /** 局面写真は画像のみ許可（SVG は {@link FileTypeValidator#BLOCKED_CONTENT_TYPES} で除外）。 */
    public static final Set<String> ALLOWED_CONTENT_TYPES = FileTypeValidator.ALLOWED_IMAGE_TYPES;

    private final MatchAttachmentRepository attachmentRepository;
    private final MatchService matchService;
    private final MatchAccessService matchAccessService;
    private final StorageService storageService;

    // ─────────────────────────────────────────────
    // 1. presign（アップロード URL 発行・記録権限必須）
    // ─────────────────────────────────────────────

    /**
     * 局面写真アップロード用の presigned PUT URL を発行する（03 §C.7a）。
     *
     * <p>親 match をテナント取得（不在/越境は 404）→ 記録権限を検証 → MIME（SVG 除外）・サイズ・件数を検証 →
     * server 採番の fileKey で presign を発行する。</p>
     *
     * @param matchId        親 match ID
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     * @param contentType    MIME
     * @param fileSize       バイト数
     * @return uploadUrl / fileKey / 有効期限
     */
    public PresignResult generateUploadUrl(UUID matchId, Long organizationId, Long actorUserId,
                                           String contentType, Long fileSize) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);

        validateContentType(contentType);
        validateFileSize(fileSize);
        validateAttachmentCount(matchId);

        String fileKey = buildFileKey(match, matchId);
        PresignedUploadResult result = storageService.generateUploadUrl(fileKey, contentType, PRESIGN_TTL);

        log.info("局面写真 presign 発行: matchId={}, fileKey={}, actor={}", matchId, fileKey, actorUserId);
        return PresignResult.builder()
                .uploadUrl(result.uploadUrl())
                .fileKey(fileKey)
                .expiresInSeconds(result.expiresInSeconds())
                .build();
    }

    // ─────────────────────────────────────────────
    // 2. 確定（メタデータ登録・記録権限必須）
    // ─────────────────────────────────────────────

    /**
     * presign 後のアップロード完了を受けて添付メタデータを登録する（03 §C.7a）。
     * 確定時も MIME / サイズ / 件数を再検証する（症状を隠さない）。
     */
    @Transactional
    public MatchAttachmentEntity confirmAttachment(UUID matchId, Long organizationId, Long actorUserId,
                                                   ConfirmCommand command) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);

        if (command == null || command.getFileKey() == null || command.getFileKey().isBlank()) {
            throw new BusinessException(MatchErrorCode.MATCH_024);
        }
        validateContentType(command.getContentType());
        validateFileSize(command.getFileSize());
        validateAttachmentCount(matchId);

        MatchAttachmentEntity attachment = MatchAttachmentEntity.builder()
                .matchId(matchId)
                .fileKey(command.getFileKey())
                .originalFilename(command.getOriginalFilename())
                .contentType(command.getContentType())
                .fileSize(command.getFileSize())
                .createdBy(actorUserId)
                .build();

        MatchAttachmentEntity saved = attachmentRepository.save(attachment);
        log.info("局面写真 確定: matchId={}, attachmentId={}, actor={}", matchId, saved.getId(), actorUserId);
        return saved;
    }

    // ─────────────────────────────────────────────
    // 3. 一覧取得（閲覧可視性は Controller が F00 委譲）
    // ─────────────────────────────────────────────

    /**
     * 指定試合の局面写真一覧を取得する（match_id スコープ・二段アクセス）。
     *
     * @param matchId        親 match ID
     * @param organizationId 認証テナント
     * @return 添付一覧（作成日時昇順）
     */
    public List<MatchAttachmentEntity> listAttachments(UUID matchId, Long organizationId) {
        matchService.getMatchOrThrow(matchId, organizationId);
        return attachmentRepository.findByMatchIdOrderByCreatedAtAsc(matchId);
    }

    // ─────────────────────────────────────────────
    // 4. ダウンロード URL 発行（短命・生 key は返さない）
    // ─────────────────────────────────────────────

    /**
     * 局面写真の短命 presigned GET URL を発行する（IDOR 逆引き・03 §C.7a）。
     *
     * @param matchId        親 match ID
     * @param attachmentId   添付 ID
     * @param organizationId 認証テナント
     * @return 短命ダウンロード URL（秒）
     */
    public DownloadUrl generateDownloadUrl(UUID matchId, UUID attachmentId, Long organizationId) {
        matchService.getMatchOrThrow(matchId, organizationId);
        MatchAttachmentEntity attachment = getAttachmentInMatchOrThrow(matchId, attachmentId);
        String downloadUrl = storageService.generateDownloadUrl(attachment.getFileKey(), DOWNLOAD_TTL);
        return DownloadUrl.builder()
                .downloadUrl(downloadUrl)
                .expiresInSeconds(DOWNLOAD_TTL.toSeconds())
                .build();
    }

    // ─────────────────────────────────────────────
    // 5. 削除（記録権限必須・R2 ベストエフォート削除）
    // ─────────────────────────────────────────────

    /**
     * 局面写真を削除する（記録権限・03 §C.7a）。R2 はベストエフォート削除する。
     *
     * @param matchId        親 match ID
     * @param attachmentId   添付 ID
     * @param organizationId 認証テナント
     * @param actorUserId    操作者ユーザー ID
     */
    @Transactional
    public void deleteAttachment(UUID matchId, UUID attachmentId, Long organizationId, Long actorUserId) {
        MatchEntity match = matchService.getMatchOrThrow(matchId, organizationId);
        matchAccessService.assertCanRecordTimeline(actorUserId, match);
        MatchAttachmentEntity attachment = getAttachmentInMatchOrThrow(matchId, attachmentId);

        String fileKey = attachment.getFileKey();
        attachmentRepository.delete(attachment);
        if (fileKey != null) {
            try {
                storageService.delete(fileKey);
            } catch (Exception e) {
                log.warn("R2 オブジェクト削除失敗（ベストエフォート）: fileKey={}, error={}", fileKey, e.getMessage());
            }
        }
        log.info("局面写真 削除: matchId={}, attachmentId={}, actor={}", matchId, attachmentId, actorUserId);
    }

    // ─────────────────────────────────────────────
    // IDOR 帰属チェーン（子 ID 直引き禁止・必ず match_id スコープ）
    // ─────────────────────────────────────────────

    /**
     * 親子 match_id 帰属を検証して添付を取得する（03 §C.7a）。
     *
     * <p>{@code findById} で取得した後に <b>match_id 一致を必ず検証</b>する。不一致（別 match の添付 ID 指定）は
     * 404 で統一し存在を漏らさない（IDOR 遮断）。</p>
     */
    private MatchAttachmentEntity getAttachmentInMatchOrThrow(UUID matchId, UUID attachmentId) {
        MatchAttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(MatchErrorCode.MATCH_031));
        if (!matchId.equals(attachment.getMatchId())) {
            throw new BusinessException(MatchErrorCode.MATCH_031);
        }
        return attachment;
    }

    // ─────────────────────────────────────────────
    // 検証
    // ─────────────────────────────────────────────

    /** MIME ホワイトリスト（画像のみ）＋ SVG 等のブロックリスト（03 §C.7a・400）。 */
    private void validateContentType(String contentType) {
        if (FileTypeValidator.isBlocked(contentType)
                || !FileTypeValidator.isAllowed(contentType, ALLOWED_CONTENT_TYPES)) {
            throw new BusinessException(MatchErrorCode.MATCH_032);
        }
    }

    /** サイズ上限（10MB・03 §C.7a・400）。 */
    private void validateFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0 || fileSize > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(MatchErrorCode.MATCH_033);
        }
    }

    /** 件数上限（03 §C.7a・400）。 */
    private void validateAttachmentCount(UUID matchId) {
        if (attachmentRepository.countByMatchId(matchId) >= MAX_ATTACHMENTS_PER_MATCH) {
            throw new BusinessException(MatchErrorCode.MATCH_034);
        }
    }

    /** fileKey: match/{matchId}/{uuid}（server 採番・クライアント任意 key を信用しない）。 */
    private String buildFileKey(MatchEntity match, UUID matchId) {
        return "match/" + match.getOrganizationId() + "/" + matchId + "/" + UUID.randomUUID();
    }

    /** presign 発行結果（uploadUrl / fileKey / 有効期限）。 */
    @Getter
    @Builder
    public static class PresignResult {
        private final String uploadUrl;
        private final String fileKey;
        private final long expiresInSeconds;
    }

    /** 添付確定コマンド（presign で得た fileKey を含む）。 */
    @Getter
    @Builder
    public static class ConfirmCommand {
        private final String fileKey;
        private final String originalFilename;
        private final String contentType;
        private final Long fileSize;
    }

    /** 短命ダウンロード URL。 */
    @Getter
    @Builder
    public static class DownloadUrl {
        private final String downloadUrl;
        private final long expiresInSeconds;
    }
}
