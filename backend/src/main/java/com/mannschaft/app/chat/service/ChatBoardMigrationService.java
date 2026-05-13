package com.mannschaft.app.chat.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * チャットスレッド→掲示板スレッド移行サービス。
 * <p>
 * depth >= 10 の深いスレッドを掲示板スレッドに移行するユースケースを担当する。
 * BulletinThreadService はチャットドメインと異なるため、
 * TODO: 将来はイベント駆動（ChatBoardMigrationRequestedEvent）でドメイン間依存を排除予定。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
// TODO: chatドメインがbulletinドメイン（BulletinThreadService）をまたいでいる。
//       将来はChatBoardMigrationRequestedEventで分離予定。Phase F04.2
@Transactional
public class ChatBoardMigrationService {

    /** 掲示板移行時のデフォルト優先度 */
    private static final String DEFAULT_PRIORITY = "INFO";
    /** 掲示板移行時のデフォルト既読管理モード */
    private static final String DEFAULT_READ_TRACKING = "COUNT_ONLY";
    /** 履歴コピー時のシステムメッセージ source_type */
    private static final String SOURCE_TYPE_CHAT = "CHAT_MIGRATION";
    /** 移行完了システムメッセージ本文 */
    private static final String MIGRATION_SYSTEM_MESSAGE = "このスレッドは掲示板に移行されました";

    private final ChatMessageRepository messageRepository;
    private final BulletinThreadService bulletinThreadService;
    private final ChatMessageService chatMessageService;
    private final ChatChannelMemberRepository memberRepository;

    /**
     * チャットスレッドを掲示板スレッドに移行する。
     *
     * @param rootMessageId  移行するスレッドのルートメッセージID
     * @param targetBoardId  移行先の掲示板カテゴリID（ScopeId として使用）
     * @param scopeType      移行先のスコープ種別
     * @param categoryId     移行先の掲示板カテゴリID
     * @param title          掲示板スレッドのタイトル
     * @param copyHistory    true の場合、チャットの返信履歴を掲示板本文に含める
     * @param requesterId    移行操作者のユーザーID
     * @return 作成された掲示板スレッドのID
     */
    public Long migrateToBoard(
            Long rootMessageId,
            Long targetBoardId,
            ScopeType scopeType,
            Long categoryId,
            String title,
            boolean copyHistory,
            Long requesterId) {

        // ルートメッセージの存在確認
        ChatMessageEntity root = messageRepository.findById(rootMessageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        // チャンネルメンバーシップ確認
        if (!memberRepository.existsByChannelIdAndUserId(root.getChannelId(), requesterId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        // 本文の構築（コピーあり: チャット履歴を引用形式で結合）
        String body = buildBoardBody(root, copyHistory);

        // 掲示板スレッド作成
        CreateThreadRequest createRequest = new CreateThreadRequest(
                categoryId,
                title,
                body,
                DEFAULT_PRIORITY,
                DEFAULT_READ_TRACKING,
                SOURCE_TYPE_CHAT,
                rootMessageId
        );

        // TODO: chatドメインがbulletinドメインをまたいでいる。将来はイベント駆動化予定
        com.mannschaft.app.bulletin.dto.ThreadResponse bulletinThread =
                bulletinThreadService.createThread(scopeType, targetBoardId, requesterId, createRequest);

        // 移行完了システムメッセージをチャットスレッドに追加
        addMigrationNotice(root.getChannelId(), rootMessageId, bulletinThread.getId(), requesterId);

        log.info("チャット→掲示板移行完了: rootMessageId={}, bulletinThreadId={}, requesterId={}",
                rootMessageId, bulletinThread.getId(), requesterId);

        return bulletinThread.getId();
    }

    /**
     * 掲示板スレッドの本文を構築する。
     *
     * @param root        ルートメッセージエンティティ
     * @param copyHistory true の場合、スレッド内の全返信を引用形式で結合する
     * @return 掲示板スレッド本文
     */
    private String buildBoardBody(ChatMessageEntity root, boolean copyHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append(root.getBody());

        if (copyHistory) {
            // root_id でスレッド全返信を取得（最大500件）
            Pageable pageable = PageRequest.of(0, 500);
            List<ChatMessageEntity> replies = messageRepository
                    .findByRootIdAndDeletedAtIsNullOrderByCreatedAtAsc(root.getId(), pageable)
                    .getContent();

            if (!replies.isEmpty()) {
                sb.append("\n\n--- チャット履歴 ---");
                replies.stream()
                        .sorted(Comparator.comparing(ChatMessageEntity::getCreatedAt))
                        .forEach(reply -> {
                            sb.append("\n> ");
                            sb.append(reply.getBody());
                        });
            }
        }

        return sb.toString();
    }

    /**
     * チャットスレッドのルートメッセージに移行完了システムメッセージを追加する。
     *
     * @param channelId      チャンネルID
     * @param rootMessageId  ルートメッセージID
     * @param bulletinId     作成された掲示板スレッドID
     * @param requesterId    操作者ユーザーID
     */
    private void addMigrationNotice(Long channelId, Long rootMessageId, Long bulletinId, Long requesterId) {
        ChatMessageEntity notice = ChatMessageEntity.builder()
                .channelId(channelId)
                .senderId(null)                     // システムメッセージのため送信者なし
                .parentId(rootMessageId)
                .rootId(rootMessageId)
                .depth(1)
                .body(MIGRATION_SYSTEM_MESSAGE + "（掲示板スレッドID: " + bulletinId + "）")
                .isSystem(true)
                .build();
        messageRepository.save(notice);
    }
}
