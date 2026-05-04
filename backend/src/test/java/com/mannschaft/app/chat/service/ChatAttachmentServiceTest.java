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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F04.2 チャット添付の F13 統合クォータ連携サービスのユニットテスト。
 *
 * <p>F13 Phase 4-β:</p>
 * <ul>
 *     <li>UX ガード 500MB 超過 → {@link ChatErrorCode#ATTACHMENT_SIZE_EXCEEDED} (413)</li>
 *     <li>F13 統合クォータ超過 → {@link ChatErrorCode#ATTACHMENT_QUOTA_EXCEEDED} (409)</li>
 *     <li>スコープ判定: TEAM/ORG/PERSONAL（DM/GROUP_DM）</li>
 *     <li>recordUpload / recordDeletion の発火</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAttachmentService ユニットテスト")
class ChatAttachmentServiceTest {

    private static final Long CHANNEL_ID = 1L;
    private static final Long TEAM_ID = 50L;
    private static final Long ORG_ID = 60L;
    private static final Long SENDER_ID = 100L;
    private static final Long ATTACHMENT_ID = 999L;

    @Mock private StorageQuotaService storageQuotaService;
    @InjectMocks private ChatAttachmentService service;

    private ChatChannelEntity teamChannel(ChannelType type) {
        return setId(ChatChannelEntity.builder()
                .channelType(type)
                .teamId(TEAM_ID)
                .name("チームチャンネル")
                .createdBy(SENDER_ID)
                .build(), CHANNEL_ID);
    }

    private ChatChannelEntity orgChannel(ChannelType type) {
        return setId(ChatChannelEntity.builder()
                .channelType(type)
                .organizationId(ORG_ID)
                .name("組織チャンネル")
                .createdBy(SENDER_ID)
                .build(), CHANNEL_ID);
    }

    private ChatChannelEntity dmChannel(ChannelType type) {
        return setId(ChatChannelEntity.builder()
                .channelType(type)
                .createdBy(SENDER_ID)
                .build(), CHANNEL_ID);
    }

    private ChatMessageAttachmentEntity attachment(long size) {
        ChatMessageAttachmentEntity e = ChatMessageAttachmentEntity.builder()
                .messageId(10L).fileKey("chat/uuid/x.png")
                .fileName("x.png").fileSize(size).contentType("image/png")
                .build();
        try {
            Field f = e.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, ATTACHMENT_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private static <T> T setId(T entity, Long id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (NoSuchFieldException ignore) {
            // BaseEntity でなければ id フィールドを直接探す
            try {
                Field f = entity.getClass().getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    @Nested
    @DisplayName("checkAttachmentQuota")
    class CheckAttachmentQuota {

        @Test
        @DisplayName("正常系: TEAM_PUBLIC は TEAM スコープで checkQuota が呼ばれる")
        void 正常系_TEAM_PUBLIC() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PUBLIC);
            service.checkAttachmentQuota(ch, 1024L, SENDER_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.TEAM, TEAM_ID, 1024L);
        }

        @Test
        @DisplayName("正常系: TEAM_PRIVATE は TEAM スコープで checkQuota が呼ばれる")
        void 正常系_TEAM_PRIVATE() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PRIVATE);
            service.checkAttachmentQuota(ch, 1024L, SENDER_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.TEAM, TEAM_ID, 1024L);
        }

        @Test
        @DisplayName("正常系: ORG_PUBLIC は ORGANIZATION スコープで checkQuota が呼ばれる")
        void 正常系_ORG_PUBLIC() {
            ChatChannelEntity ch = orgChannel(ChannelType.ORG_PUBLIC);
            service.checkAttachmentQuota(ch, 2048L, SENDER_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.ORGANIZATION, ORG_ID, 2048L);
        }

        @Test
        @DisplayName("正常系: ORG_PRIVATE は ORGANIZATION スコープで checkQuota が呼ばれる")
        void 正常系_ORG_PRIVATE() {
            ChatChannelEntity ch = orgChannel(ChannelType.ORG_PRIVATE);
            service.checkAttachmentQuota(ch, 2048L, SENDER_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.ORGANIZATION, ORG_ID, 2048L);
        }

        @Test
        @DisplayName("正常系: DM は送信者の PERSONAL スコープで checkQuota が呼ばれる")
        void 正常系_DM() {
            ChatChannelEntity ch = dmChannel(ChannelType.DM);
            service.checkAttachmentQuota(ch, 4096L, SENDER_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.PERSONAL, SENDER_ID, 4096L);
        }

        @Test
        @DisplayName("正常系: GROUP_DM は送信者の PERSONAL スコープで checkQuota が呼ばれる")
        void 正常系_GROUP_DM() {
            ChatChannelEntity ch = dmChannel(ChannelType.GROUP_DM);
            service.checkAttachmentQuota(ch, 4096L, SENDER_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.PERSONAL, SENDER_ID, 4096L);
        }

        @Test
        @DisplayName("異常系: UX ガード 500MB 超過で ATTACHMENT_SIZE_EXCEEDED (413) — checkQuota は呼ばない")
        void 異常系_UXガード超過() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PUBLIC);
            long bigSize = ChatAttachmentService.UX_GUARD_LIMIT_BYTES + 1;
            assertThatThrownBy(() -> service.checkAttachmentQuota(ch, bigSize, SENDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ChatErrorCode.ATTACHMENT_SIZE_EXCEEDED);
            verify(storageQuotaService, never()).checkQuota(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("正常系: ちょうど 500MB は許可される（境界値）")
        void 境界値_500MB() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PUBLIC);
            service.checkAttachmentQuota(ch, ChatAttachmentService.UX_GUARD_LIMIT_BYTES, SENDER_ID);
            verify(storageQuotaService).checkQuota(StorageScopeType.TEAM, TEAM_ID,
                    ChatAttachmentService.UX_GUARD_LIMIT_BYTES);
        }

        @Test
        @DisplayName("異常系: F13 クォータ超過で ATTACHMENT_QUOTA_EXCEEDED (409) に変換")
        void 異常系_クォータ超過() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PUBLIC);
            willThrow(new StorageQuotaExceededException(
                    StorageScopeType.TEAM, TEAM_ID,
                    1024L, 100L * 1024 * 1024 * 1024L, 100L * 1024 * 1024 * 1024L))
                    .given(storageQuotaService)
                    .checkQuota(eq(StorageScopeType.TEAM), eq(TEAM_ID), anyLong());
            assertThatThrownBy(() -> service.checkAttachmentQuota(ch, 1024L, SENDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ChatErrorCode.ATTACHMENT_QUOTA_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("recordAttachmentUpload")
    class RecordUpload {

        @Test
        @DisplayName("正常系: TEAM_PUBLIC は TEAM スコープで recordUpload が呼ばれる")
        void 正常系_TEAM() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PUBLIC);
            ChatMessageAttachmentEntity att = attachment(1024L);
            service.recordAttachmentUpload(ch, att, SENDER_ID);
            verify(storageQuotaService).recordUpload(
                    StorageScopeType.TEAM, TEAM_ID, 1024L,
                    StorageFeatureType.CHAT,
                    "chat_message_attachments", ATTACHMENT_ID, SENDER_ID);
        }

        @Test
        @DisplayName("正常系: GROUP_DM は送信者の PERSONAL スコープで recordUpload が呼ばれる")
        void 正常系_PERSONAL() {
            ChatChannelEntity ch = dmChannel(ChannelType.GROUP_DM);
            ChatMessageAttachmentEntity att = attachment(2048L);
            service.recordAttachmentUpload(ch, att, SENDER_ID);
            verify(storageQuotaService).recordUpload(
                    StorageScopeType.PERSONAL, SENDER_ID, 2048L,
                    StorageFeatureType.CHAT,
                    "chat_message_attachments", ATTACHMENT_ID, SENDER_ID);
        }

        @Test
        @DisplayName("ガード: サイズ 0 では recordUpload を呼ばない")
        void ガード_サイズ0() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PUBLIC);
            ChatMessageAttachmentEntity att = attachment(0L);
            service.recordAttachmentUpload(ch, att, SENDER_ID);
            verify(storageQuotaService, never()).recordUpload(
                    any(StorageScopeType.class), anyLong(), anyLong(),
                    any(StorageFeatureType.class), anyString(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("recordAttachmentDeletion")
    class RecordDeletion {

        @Test
        @DisplayName("正常系: ORG_PRIVATE は ORGANIZATION スコープで recordDeletion が呼ばれる")
        void 正常系_ORG() {
            ChatChannelEntity ch = orgChannel(ChannelType.ORG_PRIVATE);
            ChatMessageAttachmentEntity att = attachment(8192L);
            // actor != sender でも、PERSONAL スコープは sender ではなく channel スコープなので
            // ORG では actorId / senderId の差は scope_id に影響しないが actor_id ログには反映される
            Long deleterId = 200L;
            service.recordAttachmentDeletion(ch, att, deleterId, SENDER_ID);
            verify(storageQuotaService).recordDeletion(
                    StorageScopeType.ORGANIZATION, ORG_ID, 8192L,
                    StorageFeatureType.CHAT,
                    "chat_message_attachments", ATTACHMENT_ID, deleterId);
        }

        @Test
        @DisplayName("正常系: DM 削除は元送信者の PERSONAL スコープから減算")
        void 正常系_DM_PERSONAL() {
            ChatChannelEntity ch = dmChannel(ChannelType.DM);
            ChatMessageAttachmentEntity att = attachment(4096L);
            // 削除実行者と送信者が異なる（管理操作等）。PERSONAL スコープは送信者基準
            Long deleterId = 200L;
            service.recordAttachmentDeletion(ch, att, deleterId, SENDER_ID);
            verify(storageQuotaService).recordDeletion(
                    StorageScopeType.PERSONAL, SENDER_ID, 4096L,
                    StorageFeatureType.CHAT,
                    "chat_message_attachments", ATTACHMENT_ID, deleterId);
        }

        @Test
        @DisplayName("ガード: サイズ 0 では recordDeletion を呼ばない")
        void ガード_サイズ0() {
            ChatChannelEntity ch = teamChannel(ChannelType.TEAM_PUBLIC);
            ChatMessageAttachmentEntity att = attachment(0L);
            service.recordAttachmentDeletion(ch, att, SENDER_ID, SENDER_ID);
            verify(storageQuotaService, never()).recordDeletion(
                    any(StorageScopeType.class), anyLong(), anyLong(),
                    any(StorageFeatureType.class), anyString(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("resolveScope (異常系)")
    class ResolveScopeErrors {

        @Test
        @DisplayName("TEAM チャンネルで teamId が null なら IllegalStateException")
        void TEAM_teamId_null() {
            ChatChannelEntity ch = setId(ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC)
                    .build(), CHANNEL_ID);
            assertThatThrownBy(() -> service.checkAttachmentQuota(ch, 1L, SENDER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("ORG チャンネルで organizationId が null なら IllegalStateException")
        void ORG_orgId_null() {
            ChatChannelEntity ch = setId(ChatChannelEntity.builder()
                    .channelType(ChannelType.ORG_PUBLIC)
                    .build(), CHANNEL_ID);
            assertThatThrownBy(() -> service.checkAttachmentQuota(ch, 1L, SENDER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("DM スコープで userId が null なら IllegalStateException")
        void DM_userId_null() {
            ChatChannelEntity ch = setId(ChatChannelEntity.builder()
                    .channelType(ChannelType.DM)
                    .build(), CHANNEL_ID);
            assertThat(ch.getChannelType()).isEqualTo(ChannelType.DM);
            assertThatThrownBy(() -> service.checkAttachmentQuota(ch, 1L, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
