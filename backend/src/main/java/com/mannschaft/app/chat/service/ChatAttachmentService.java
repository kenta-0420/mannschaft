package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * F04.2 チャット添付ファイルの F13 統合ストレージクォータ連携サービス。
 *
 * <p>F13 Phase 4-β でチャット添付（presign / 送信時 INSERT / 論理削除）を統合クォータに接続する。
 * 主な責務:</p>
 * <ul>
 *     <li>UX ガード（1ファイル {@value #UX_GUARD_LIMIT_BYTES} バイト = 500MB）の事前チェック</li>
 *     <li>チャンネル種別に応じたスコープ判定（TEAM_PUBLIC/TEAM_PRIVATE → TEAM、ORG_PUBLIC/ORG_PRIVATE → ORG、
 *         DM/GROUP_DM → 送信者の PERSONAL）</li>
 *     <li>{@link StorageQuotaService#checkQuota} 呼び出しと {@link StorageQuotaExceededException} の
 *         {@link ChatErrorCode#ATTACHMENT_QUOTA_EXCEEDED} 変換</li>
 *     <li>{@link StorageQuotaService#recordUpload} / {@link StorageQuotaService#recordDeletion} の発火</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/cross-cutting/storage_quota.md">設計書 §11 Phase 4-β</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService {

    /** UX ガード: 1 添付ファイルあたり 500MB 上限（容量課金とは別軸）。 */
    public static final long UX_GUARD_LIMIT_BYTES = 500L * 1024 * 1024;

    /** F13 Phase 4-β: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "chat_message_attachments";

    /** F04.2 Phase 11 2-β: チャンネルアイコンの最大サイズ（2MB）。 */
    public static final long CHANNEL_ICON_MAX_BYTES = 2L * 1024 * 1024;

    /** F04.2 Phase 11 2-β: チャンネルアイコンで許可される MIME タイプ。 */
    public static final Set<String> CHANNEL_ICON_ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** F04.2 Phase 11 2-β: チャンネルアイコンの Pre-signed URL 有効期限（5 分）。 */
    private static final Duration CHANNEL_ICON_PRESIGN_TTL = Duration.ofMinutes(5);

    private final StorageQuotaService storageQuotaService;
    private final StorageService storageService;
    private final ChatChannelAccessGuard channelAccessGuard;

    /**
     * presign 直前のクォータ・UX ガード事前チェック。
     *
     * <p>呼び出し順序:</p>
     * <ol>
     *     <li>UX ガード（500MB）— 超過は {@link ChatErrorCode#ATTACHMENT_SIZE_EXCEEDED} (413)</li>
     *     <li>F13 統合クォータチェック — 超過は {@link ChatErrorCode#ATTACHMENT_QUOTA_EXCEEDED} (409)</li>
     * </ol>
     *
     * @param channel  対象チャンネル（スコープ判定の基準）
     * @param fileSize アップロードしようとしているファイルサイズ（バイト）
     * @param userId   送信者ユーザー ID（DM 系チャンネル時の PERSONAL スコープに使用）
     */
    public void checkAttachmentQuota(ChatChannelEntity channel, long fileSize, Long userId) {
        // 1. UX ガード: 1ファイル 500MB 上限（容量課金とは別軸 — 大容量は F05.5 へ誘導）
        if (fileSize > UX_GUARD_LIMIT_BYTES) {
            log.info("チャット添付の UX ガード超過: channelId={}, userId={}, requested={}, limit={}",
                    channel.getId(), userId, fileSize, UX_GUARD_LIMIT_BYTES);
            throw new BusinessException(ChatErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }

        ScopeResolution scope = resolveScope(channel, userId);

        // 2. F13 統合クォータチェック
        try {
            storageQuotaService.checkQuota(scope.scopeType(), scope.scopeId(), fileSize);
        } catch (StorageQuotaExceededException e) {
            log.info("チャット添付の F13 クォータ超過: channelId={}, scope={}/{}, requested={}, used={}, included={}",
                    channel.getId(), scope.scopeType(), scope.scopeId(),
                    e.getRequestedBytes(), e.getUsedBytes(), e.getIncludedBytes());
            throw new BusinessException(ChatErrorCode.ATTACHMENT_QUOTA_EXCEEDED, e);
        }
    }

    /**
     * 添付ファイル INSERT 直後の使用量加算。
     *
     * @param channel    対象チャンネル
     * @param attachment INSERT 済みの添付エンティティ
     * @param actorId    操作者ユーザー ID（送信者）
     */
    public void recordAttachmentUpload(ChatChannelEntity channel,
                                       ChatMessageAttachmentEntity attachment,
                                       Long actorId) {
        long size = attachment.getFileSize() != null ? attachment.getFileSize() : 0L;
        if (size <= 0) {
            return;
        }
        ScopeResolution scope = resolveScope(channel, actorId);
        storageQuotaService.recordUpload(
                scope.scopeType(), scope.scopeId(), size,
                StorageFeatureType.CHAT,
                REFERENCE_TYPE, attachment.getId(), actorId);
    }

    /**
     * メッセージ論理削除に伴う添付ファイルの使用量減算。
     *
     * @param channel    対象チャンネル
     * @param attachment 削除対象の添付エンティティ
     * @param actorId    操作者ユーザー ID（削除実行者）
     * @param senderId   メッセージ送信者ユーザー ID（PERSONAL スコープ判定に使用）
     */
    public void recordAttachmentDeletion(ChatChannelEntity channel,
                                         ChatMessageAttachmentEntity attachment,
                                         Long actorId, Long senderId) {
        long size = attachment.getFileSize() != null ? attachment.getFileSize() : 0L;
        if (size <= 0) {
            return;
        }
        ScopeResolution scope = resolveScope(channel, senderId);
        storageQuotaService.recordDeletion(
                scope.scopeType(), scope.scopeId(), size,
                StorageFeatureType.CHAT,
                REFERENCE_TYPE, attachment.getId(), actorId);
    }

    /**
     * チャンネル種別に応じてストレージスコープを解決する。
     *
     * <ul>
     *     <li>TEAM_PUBLIC / TEAM_PRIVATE → TEAM (channel.teamId)</li>
     *     <li>ORG_PUBLIC / ORG_PRIVATE → ORGANIZATION (channel.organizationId)</li>
     *     <li>DM / GROUP_DM → PERSONAL (sender userId)</li>
     * </ul>
     */
    public ScopeResolution resolveScope(ChatChannelEntity channel, Long userId) {
        ChannelType type = channel.getChannelType();
        if (type == null) {
            throw new IllegalStateException("ChannelType is null for channelId=" + channel.getId());
        }
        return switch (type) {
            case TEAM_PUBLIC, TEAM_PRIVATE -> {
                if (channel.getTeamId() == null) {
                    throw new IllegalStateException(
                            "TEAM channel has null teamId: channelId=" + channel.getId());
                }
                yield new ScopeResolution(StorageScopeType.TEAM, channel.getTeamId());
            }
            case ORG_PUBLIC, ORG_PRIVATE -> {
                if (channel.getOrganizationId() == null) {
                    throw new IllegalStateException(
                            "ORG channel has null organizationId: channelId=" + channel.getId());
                }
                yield new ScopeResolution(StorageScopeType.ORGANIZATION, channel.getOrganizationId());
            }
            case DM, GROUP_DM -> {
                if (userId == null) {
                    throw new IllegalStateException(
                            "DM/GROUP_DM scope requires userId: channelId=" + channel.getId());
                }
                yield new ScopeResolution(StorageScopeType.PERSONAL, userId);
            }
            // F17.1 Phase 1: 村ロビーチャネルは添付スコープ未サポート（村スコープのストレージは Phase 2 以降）
            case VILLAGE_LOBBY -> throw new UnsupportedOperationException(
                    "VILLAGE_LOBBY 添付はまだサポートされていません (channelId=" + channel.getId() + ")");
            // F08.7.1: 大会/ディビジョン連絡チャットは team/org に属さない横断スペースのため、
            // 添付は操作者の PERSONAL クォータに計上する（VILLAGE_LOBBY と同方針）。
            case TOURNAMENT_CHAT, TOURNAMENT_DIVISION_CHAT -> {
                if (userId == null) {
                    throw new IllegalStateException(
                            "TOURNAMENT chat scope requires userId: channelId=" + channel.getId());
                }
                yield new ScopeResolution(StorageScopeType.PERSONAL, userId);
            }
            // イベント専用チャンネルはチームまたは組織スコープにフォールバックする
            case EVENT_CHAT -> {
                if (channel.getTeamId() != null) {
                    yield new ScopeResolution(StorageScopeType.TEAM, channel.getTeamId());
                } else if (channel.getOrganizationId() != null) {
                    yield new ScopeResolution(StorageScopeType.ORGANIZATION, channel.getOrganizationId());
                } else {
                    throw new IllegalStateException(
                            "EVENT_CHAT channel has null teamId and organizationId: channelId=" + channel.getId());
                }
            }
        };
    }

    /** 解決されたストレージスコープ。 */
    public record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}

    /**
     * F04.2 Phase 11 第二陣 2-β: チャンネルアイコン用 Pre-signed URL を発行する。
     *
     * <p>メッセージ添付用 {@link #checkAttachmentQuota} とは別経路。
     * チャンネルアイコンは {@code chat_channels.icon_key} に保存される独立リソースであり、
     * メッセージ添付テーブルに INSERT しない / F13 統合クォータも計上しない。</p>
     *
     * <p>処理順序:</p>
     * <ol>
     *     <li>認可: 呼び出しユーザーがチャンネルの OWNER / ADMIN であること
     *         （{@link ChatErrorCode#CHANNEL_ICON_PERMISSION_DENIED} = 403）</li>
     *     <li>MIME ホワイトリスト検証
     *         （{@link ChatErrorCode#ICON_CONTENT_TYPE_INVALID} = 400）</li>
     *     <li>サイズ上限検証（2MB）
     *         （{@link ChatErrorCode#ICON_SIZE_EXCEEDED} = 413）</li>
     *     <li>R2 オブジェクトキー組み立て: {@code chat/{scopeType}/{scopeId}/icons/{uuid}/{fileName}}</li>
     *     <li>{@link StorageService#generateUploadUrl} で 5 分有効の署名 URL を発行</li>
     * </ol>
     *
     * @param channel       対象チャンネル
     * @param currentUserId 操作者ユーザー ID
     * @param contentType   アイコンの MIME タイプ
     * @param fileSize      アイコンのファイルサイズ（バイト）
     * @param fileName      アイコンのファイル名（オブジェクトキーの末尾に付与）
     * @return Pre-signed URL 結果（uploadUrl / s3Key / expiresInSeconds）
     */
    public PresignedUploadResult presignChannelIconUpload(ChatChannelEntity channel,
                                                          Long currentUserId,
                                                          String contentType,
                                                          long fileSize,
                                                          String fileName) {
        // 1. 認可: 当該チャンネルの OWNER / ADMIN のみアイコンを変更できる。
        channelAccessGuard.requireChannelManagerRole(
                channel.getId(), currentUserId, ChatErrorCode.CHANNEL_ICON_PERMISSION_DENIED);

        // 2. MIME ホワイトリスト
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!CHANNEL_ICON_ALLOWED_CONTENT_TYPES.contains(normalizedType)) {
            log.info("チャンネルアイコン presign 拒否（MIME 不許可）: channelId={}, contentType={}",
                    channel.getId(), contentType);
            throw new BusinessException(ChatErrorCode.ICON_CONTENT_TYPE_INVALID);
        }

        // 3. サイズ上限（2MB）
        if (fileSize > CHANNEL_ICON_MAX_BYTES) {
            log.info("チャンネルアイコン presign 拒否（サイズ超過）: channelId={}, size={}, limit={}",
                    channel.getId(), fileSize, CHANNEL_ICON_MAX_BYTES);
            throw new BusinessException(ChatErrorCode.ICON_SIZE_EXCEEDED);
        }

        // 4. オブジェクトキー組み立て
        //    既存の resolveScope() を流用してチャンネル種別ごとのスコープパスを得る。
        //    VILLAGE_LOBBY は未サポート例外を投げるため、ここでは PERSONAL フォールバックで操作者を使う。
        ScopeResolution scope = resolveIconScope(channel, currentUserId);
        String safeFileName = sanitizeFileName(fileName);
        String fileKey = "chat/" + scope.scopeType().name()
                + "/" + scope.scopeId()
                + "/icons/" + UUID.randomUUID()
                + "/" + safeFileName;

        // 5. Pre-signed URL 発行（5 分有効）
        PresignedUploadResult result = storageService.generateUploadUrl(
                fileKey, normalizedType, CHANNEL_ICON_PRESIGN_TTL);
        log.info("チャンネルアイコン presign 発行: channelId={}, userId={}, fileKey={}",
                channel.getId(), currentUserId, fileKey);
        return result;
    }

    /**
     * チャンネルアイコン用のスコープ解決。
     * VILLAGE_LOBBY は {@link #resolveScope} が例外を投げるため、
     * 操作者ベースの PERSONAL スコープにフォールバックする。
     */
    private ScopeResolution resolveIconScope(ChatChannelEntity channel, Long userId) {
        if (channel.getChannelType() == ChannelType.VILLAGE_LOBBY) {
            return new ScopeResolution(StorageScopeType.PERSONAL, userId);
        }
        return resolveScope(channel, userId);
    }

    /** R2 オブジェクトキーに使うため簡易サニタイズ。スラッシュ・空白・制御文字を除去。 */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "icon";
        }
        String cleaned = fileName.replaceAll("[\\\\/\\s\\p{Cntrl}]", "_");
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(cleaned.length() - 100);
        }
        return cleaned;
    }
}
