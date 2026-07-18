package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 承諾型招待の chat ドメイン側関心事（DM 相手導出・招待カード投稿）を担うサービス（F04.12）。
 *
 * <p>越境の chat 側を本サービスに閉じる（原則5・設計書 §5）。role ドメインの
 * {@link com.mannschaft.app.role.service.MembershipInviteService} からドメイン間 Service 呼び出しで
 * 使用される。chat の Repository のみを参照する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ChatMembershipInviteCardService {

    /** last_message_preview 列（VARCHAR(100)）に合わせた最大長。 */
    private static final int PREVIEW_LENGTH = 100;

    private final ChatChannelRepository channelRepository;
    private final ChatChannelMemberRepository memberRepository;
    private final ChatMessageRepository messageRepository;

    /**
     * DM チャンネルの相手（実行ユーザー以外の1名）を宛先として導出する（設計書 B-9）。
     *
     * <p>検証:</p>
     * <ul>
     *   <li>チャンネルが存在すること（不在は 404 {@code CHAT_001}）</li>
     *   <li>{@code channel_type == DM} であること（{@code GROUP_DM}・非 DM は 422）</li>
     *   <li>メンバーが実行ユーザーを含め 2 名であること（DM 2 名前提ガード・2 名でなければ 422）</li>
     *   <li>実行ユーザーが当該 DM の当事者であること（非当事者は 403）</li>
     * </ul>
     *
     * @param channelId   DM チャンネル ID
     * @param actorUserId 実行ユーザー ID
     * @return DM 相手（宛先）のユーザー ID
     */
    public Long resolveDmCounterpart(Long channelId, Long actorUserId) {
        ChatChannelEntity channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(
                        ChatErrorCode.CHANNEL_NOT_FOUND, HttpStatus.NOT_FOUND));

        // GROUP_DM を含め DM 以外は招待の入口にできない（isDm() は GROUP_DM も true のため厳密比較する）。
        if (channel.getChannelType() != ChannelType.DM) {
            throw new BusinessException(ChatErrorCode.CHANNEL_NOT_DM, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        List<ChatChannelMemberEntity> members =
                memberRepository.findByChannelIdOrderByJoinedAtAsc(channelId);
        if (members.size() != 2) {
            // DM は 2 レコード固定。2 名でなければ宛先を一意に導出できない。
            throw new BusinessException(ChatErrorCode.CHANNEL_NOT_DM, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        boolean actorIsMember = members.stream()
                .anyMatch(m -> actorUserId.equals(m.getUserId()));
        if (!actorIsMember) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED, HttpStatus.FORBIDDEN);
        }

        return members.stream()
                .map(ChatChannelMemberEntity::getUserId)
                .filter(uid -> !actorUserId.equals(uid))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ChatErrorCode.CHANNEL_NOT_DM, HttpStatus.UNPROCESSABLE_ENTITY));
    }

    /**
     * DM に招待カード（{@code message_type = 'INVITE_CARD'}）を投稿する（設計書 §5 手順8）。
     *
     * <p>越境の chat 側を単独 {@code @Transactional} に閉じる。body は NOT NULL 制約を満たす内部マーカー
     * 固定文字列を格納し（表示は inviteData + i18n で都度描画・設計書 M-2）、
     * {@code last_message_preview} は投稿時点の実文字列を格納する（D-14）。</p>
     *
     * @param channelId 投稿先 DM チャンネル ID
     * @param senderId  発行者（送信者）ユーザー ID
     * @param tokenId   参照する招待トークン ID
     * @param preview   一覧表示用プレビュー実文字列
     * @return 投稿された招待カードメッセージ ID
     */
    @Transactional
    public Long postInviteCard(Long channelId, Long senderId, Long tokenId, String preview) {
        ChatMessageEntity card = ChatMessageEntity.builder()
                .channelId(channelId)
                .senderId(senderId)
                .messageType("INVITE_CARD")
                .inviteTokenId(tokenId)
                .isSystem(false)
                .body("[招待カード]")
                .build();
        ChatMessageEntity saved = messageRepository.save(card);

        // チャンネルの最終メッセージ情報を更新（既存 sendMessage と同じ denormalize 方針）。
        String trimmed = preview != null && preview.length() > PREVIEW_LENGTH
                ? preview.substring(0, PREVIEW_LENGTH)
                : preview;
        channelRepository.findById(channelId)
                .ifPresent(c -> c.updateLastMessage(LocalDateTime.now(), trimmed));

        log.info("招待カード投稿完了: cardMessageId={}, channelId={}, inviteTokenId={}",
                saved.getId(), channelId, tokenId);
        return saved.getId();
    }
}
