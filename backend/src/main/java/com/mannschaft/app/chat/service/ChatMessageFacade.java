package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.dto.ActiveThreadItemResponse;
import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.dto.ThreadResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.common.CursorPagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * チャンネル ID・メッセージ ID を指定するメッセージ操作の認可を、トランザクションの外で済ませるファサード
 * （CMP-261105-1507。型は {@code AuthzTxFacadeRegistryArchTest} の登録表に従う）。
 *
 * <p>流れは「Controller（REST・WebSocket）→ 本クラス（tx なし・{@link ChatChannelAccessGuard} で認可と存在秘匿）→
 * tx 本体（{@link ChatMessageService}・認可なし）」である。閲覧・投稿の認可が読む他ドメイン
 * （membership / role / tournament / event / village）の行を、チャットの書き込みトランザクションに巻き込まない
 * （CLAUDE.md DB 設計の原則 #5・番人 D-3T）。</p>
 *
 * <p>本クラスに {@code @Transactional} を付けてはならない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageFacade {

    private final ChatMessageService messageService;
    private final ChatChannelService channelService;
    private final ChatChannelAccessGuard channelAccessGuard;

    /** チャンネルのメッセージ一覧を取得する（閲覧認可）。 */
    public CursorPagedResponse<MessageResponse> listMessages(
            Long channelId, Long viewerId, Long cursor, Integer limit, String direction) {
        requireChannelView(channelId, viewerId);
        return messageService.listMessages(channelId, viewerId, cursor, limit, direction);
    }

    /** チャンネルのメッセージを検索する（閲覧認可）。 */
    public List<MessageResponse> searchMessages(Long channelId, String keyword, Integer limit, Long userId) {
        requireChannelView(channelId, userId);
        return messageService.searchMessages(channelId, keyword, limit, userId);
    }

    /** アクティブスレッド一覧を取得する（閲覧認可）。 */
    public CursorPagedResponse<ActiveThreadItemResponse> getActiveThreads(
            Long channelId, Long userId, String cursor, Integer limit) {
        requireChannelView(channelId, userId);
        return messageService.getActiveThreads(channelId, userId, cursor, limit);
    }

    /**
     * メッセージを送信する（投稿認可）。大会/ディビジョン連絡チャットは connack 認可（canPost）、
     * 通常チャンネルはチャンネルメンバーであることを要求する。REST と WebSocket の両経路がここを通る。
     */
    public MessageResponse sendMessage(Long channelId, SendMessageRequest request, Long senderId) {
        channelAccessGuard.checkChannelPostAccess(
                channelService.findChannelOrThrow(channelId), senderId, ChatErrorCode.CHANNEL_NOT_FOUND);
        return messageService.sendMessage(channelId, request, senderId);
    }

    /** メッセージを編集する（送信者本人のみ）。 */
    public MessageResponse editMessage(Long messageId, EditMessageRequest request, Long userId) {
        ChatMessageEntity message = messageService.findMessageOrThrow(messageId);
        channelAccessGuard.requireMessageOwner(channelAccessGuard.channelOfMessage(message), message, userId);
        return messageService.editMessage(messageId, request, userId);
    }

    /**
     * メッセージを削除する（F04.2 §5: 投稿者本人 / チャンネル OWNER・ADMIN /
     * スコープ ADMIN / SYSTEM_ADMIN。DEPUTY_ADMIN は MANAGE_CHAT 登録後の CMP-261107-2134 で広げる）。
     */
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessageEntity message = messageService.findMessageOrThrow(messageId);
        ChatChannelEntity channel = channelAccessGuard.channelOfMessage(message);
        boolean moderation = channelAccessGuard.requireMessageDeletable(channel, message, userId);
        messageService.deleteMessage(messageId, userId);
        if (moderation) {
            // F04.2 §6 監査: CHAT_MESSAGE_DELETED_BY_ADMIN（投稿者本人以外による削除）
            log.info("CHAT_MESSAGE_DELETED_BY_ADMIN: messageId={}, channelId={}, operatorUserId={}, senderId={}",
                    messageId, channel.getId(), userId, message.getSenderId());
        }
    }

    /** スレッドの全返信を取得する（ルートメッセージの閲覧認可。拒否は不在メッセージと同一応答）。 */
    public ThreadResponse getThread(Long messageId, String cursor, Integer limit, Long userId) {
        channelAccessGuard.checkMessageViewAccess(messageService.findMessageOrThrow(messageId), userId);
        return messageService.getThread(messageId, cursor, limit, userId);
    }

    /**
     * メッセージをピン留め/解除する。モデレーション相当として投稿認可を要求する
     * （大会連絡チャットは代表/主催者、通常チャンネルはチャンネルメンバー）。
     */
    public MessageResponse togglePin(Long messageId, boolean pinned, Long userId) {
        ChatMessageEntity message = messageService.findMessageOrThrow(messageId);
        channelAccessGuard.checkChannelPostAccess(
                channelAccessGuard.channelOfMessage(message), userId, ChatErrorCode.MESSAGE_NOT_FOUND);
        return messageService.togglePin(messageId, pinned, userId);
    }

    /**
     * メッセージを転送する。転送元の閲覧認可（拒否は不在メッセージと同一応答）を、転送先の解決より先に行う
     * （順序が逆だと転送元の実在が漏れる）。転送先には投稿認可を要求する。
     */
    public MessageResponse forwardMessage(Long messageId, ForwardMessageRequest request, Long userId) {
        channelAccessGuard.checkMessageViewAccess(messageService.findMessageOrThrow(messageId), userId);
        channelAccessGuard.checkChannelPostAccess(
                channelService.findChannelOrThrow(request.getTargetChannelId()), userId,
                ChatErrorCode.CHANNEL_NOT_FOUND);
        return messageService.forwardMessage(messageId, request, userId);
    }

    private void requireChannelView(Long channelId, Long userId) {
        channelAccessGuard.checkChannelViewAccess(
                channelService.findChannelOrThrow(channelId), userId, ChatErrorCode.CHANNEL_NOT_FOUND);
    }
}
