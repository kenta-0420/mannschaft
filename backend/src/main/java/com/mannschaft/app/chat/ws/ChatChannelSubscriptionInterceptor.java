package com.mannschaft.app.chat.ws;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AC-11【セキュリティ・IDOR 根治】チャットチャネルの STOMP 購読認可インターセプタ。
 *
 * <p>設計書 {@code docs/architecture/websocket_external_broker_valkey.md} §2.6 / AC-11。
 * STOMP {@code SUBSCRIBE} フレームのうち <b>{@code /topic/channels/{channelId}} 宛先のみ</b>を
 * 認可対象とし、<b>チャネルメンバーシップ（{@code chat_channel_members}）を検査</b>して非メンバーの購読を
 * {@link MessagingException} をスローして拒否する（ERROR フレーム返却・購読不成立）。
 * 既存 {@code MatchLiveSubscriptionInterceptor}・{@code EmergencyClosureSubscriptionInterceptor} と同パターン。</p>
 *
 * <h3>是正する欠陥（§2.6）</h3>
 * <p>現行は SUBSCRIBE 認可インターセプタが match live / emergency-closure の 2 destination にしか無く、
 * チャット {@code /topic/channels/{channelId}} には購読認可が存在しない。その結果、認証済みユーザーであれば
 * 自分がメンバーでない任意の channelId を購読でき、他チームのチャット本文を受信できた（既存 IDOR）。
 * 本インターセプタでメンバーシップ検査を行い閉塞する。</p>
 *
 * <h3>設計上の不変条件</h3>
 * <ul>
 *   <li><b>宛先は末尾厳密一致（{@code ^/topic/channels/(\d+)$}）</b>。{@code /topic/channels/{id}/events} 等の
 *       サブ destination は巻き込まない（events の SUBSCRIBE 認可は設計書に従い本スコープ対象外・既知課題）。</li>
 *   <li><b>チャネル以外の宛先（match live / emergency-closure / corkboard / signage 等）は素通し</b>する
 *       （パターン不一致＝認可対象外）。既存購読を壊さない。</li>
 *   <li><b>未認証（session userId=null）は拒否</b>する。チャットは非公開前提であり、未ログイン観覧は許容しない。</li>
 *   <li><b>チャネル不在／論理削除済みは拒否</b>する（存在を漏らさず安全側に倒す）。</li>
 *   <li><b>メンバー行でメンバーシップが定義される種別のみ</b>を認可対象とする（{@link #MEMBERSHIP_GATED_TYPES}）。
 *       {@code VILLAGE_LOBBY}／{@code EVENT_CHAT}／{@code TOURNAMENT_CHAT}／{@code TOURNAMENT_DIVISION_CHAT} は
 *       {@code chat_channel_members} 行を持たず、それぞれ village メンバーシップ・大会連絡スペース認可
 *       （{@code TournamentContactAccessService}）等の別ドメインのアクセスモデルで管理される。これらに
 *       メンバーシップ検査を適用すると<b>正当な利用者の購読まで一律拒否してしまう（機能破壊）</b>ため、
 *       本インターセプタでは<b>素通し</b>し、WS 層での購読認可は<b>既知課題（follow-up）</b>として PR 本文へ記載する
 *       （REST 側と同じく、これら種別は各自ドメインで別途認可される）。</li>
 * </ul>
 *
 * <p>登録は {@link com.mannschaft.app.config.WebSocketConfig} の inbound channel に
 * 認証インターセプタ（{@code WebSocketAuthChannelInterceptor}）の<b>後段</b>で行う
 * （CONNECT で確定した session userId を本インターセプタが参照するため・順序が重要）。</p>
 *
 * <p>配信トピック契約（配信側 {@code ChatMessagePublisher}・{@code ChatChannelEventPublisher} と一致）:
 * {@code /topic/channels/{channelId}}（{@code channelId} は数値）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatChannelSubscriptionInterceptor implements ChannelInterceptor {

    /** CONNECT 時に {@code WebSocketAuthChannelInterceptor} が格納する session 属性キー。 */
    private static final String SESSION_USER_ID = "userId";

    /**
     * チャットチャネルの宛先パターン。{@code /topic/channels/{channelId}} のみに<b>末尾厳密一致</b>する
     * （channelId は数値）。{@code /topic/channels/{id}/events} 等のサブ destination は意図的にマッチさせず、
     * パターン不一致＝素通し（本機能対象外）とする。
     */
    private static final Pattern CHANNEL_DESTINATION = Pattern.compile("^/topic/channels/(\\d+)$");

    /**
     * {@code chat_channel_members} 行でメンバーシップが定義される種別（購読認可の対象）。
     *
     * <p>これ以外の種別（{@code VILLAGE_LOBBY}／{@code EVENT_CHAT}／{@code TOURNAMENT_CHAT}／
     * {@code TOURNAMENT_DIVISION_CHAT}）はメンバー行を持たず、別ドメインのアクセスモデルで管理されるため、
     * ここでのメンバーシップ検査対象から除外し素通しする（クラス Javadoc の不変条件・既知課題を参照）。</p>
     */
    private static final Set<ChannelType> MEMBERSHIP_GATED_TYPES = EnumSet.of(
            ChannelType.TEAM_PUBLIC,
            ChannelType.TEAM_PRIVATE,
            ChannelType.ORG_PUBLIC,
            ChannelType.ORG_PRIVATE,
            ChannelType.DM,
            ChannelType.GROUP_DM);

    private final ChatChannelRepository chatChannelRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        // SUBSCRIBE 以外（CONNECT / SEND / UNSUBSCRIBE / DISCONNECT 等）は素通し。
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }

        Matcher matcher = CHANNEL_DESTINATION.matcher(destination);
        // チャットチャネル以外（match live / emergency-closure / corkboard / signage / events 等）は素通し。
        if (!matcher.matches()) {
            return message;
        }

        long channelId;
        try {
            channelId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            // 正規表現を通過しているため通常到達しない（数値オーバーフロー時のみ）。安全側に倒して拒否する。
            throw new MessagingException("チャネルの購読先 ID が不正です: " + destination);
        }

        Long userId = resolveUserId(accessor);

        // 未認証はチャット購読不可（チャットは非公開前提）。
        if (userId == null) {
            log.debug("チャネル購読を拒否（未認証）: destination={}", destination);
            throw new MessagingException("チャネルの購読権限がありません");
        }

        ChatChannelEntity chatChannel = chatChannelRepository.findById(channelId).orElse(null);
        // チャネル不在／論理削除済みは拒否（存在を漏らさず安全側に倒す）。
        if (chatChannel == null || chatChannel.getDeletedAt() != null) {
            log.debug("チャネル購読を拒否（不在/削除済み）: userId={}, channelId={}", userId, channelId);
            throw new MessagingException("チャネルの購読権限がありません");
        }

        // メンバー行でメンバーシップが定義される種別のみ購読認可を行う。
        // 自己管理型（村ロビー/イベント/大会）は別ドメイン認可のため素通し（既知課題・PR 本文参照）。
        if (!MEMBERSHIP_GATED_TYPES.contains(chatChannel.getChannelType())) {
            log.debug("チャネル購読を許可（メンバーシップ非依存種別・素通し）: userId={}, channelId={}, type={}",
                    userId, channelId, chatChannel.getChannelType());
            return message;
        }

        // メンバーシップ検査（既存リポジトリ経由）。非メンバーの購読は拒否＝IDOR 閉塞。
        if (!chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            log.debug("チャネル購読を拒否（非メンバー）: userId={}, channelId={}, type={}",
                    userId, channelId, chatChannel.getChannelType());
            // 購読を不成立にする（ERROR フレームが返り SUBSCRIBE は確立しない）。
            throw new MessagingException("チャネルの購読権限がありません");
        }

        log.debug("チャネル購読を許可: userId={}, channelId={}", userId, channelId);
        return message;
    }

    /**
     * CONNECT 時に {@link com.mannschaft.app.config.WebSocketAuthChannelInterceptor} が
     * session 属性へ格納した userId を取り出す。未認証（属性なし）は {@code null} を返す。
     */
    private Long resolveUserId(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }
        Object value = sessionAttributes.get(SESSION_USER_ID);
        return (value instanceof Long longValue) ? longValue : null;
    }
}
