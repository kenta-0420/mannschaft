package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final StorageQuotaService storageQuotaService;

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
        };
    }

    /** 解決されたストレージスコープ。 */
    public record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}
}
