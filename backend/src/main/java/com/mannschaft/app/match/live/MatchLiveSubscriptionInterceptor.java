package com.mannschaft.app.match.live;

import com.mannschaft.app.match.service.MatchAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F08.10 / 07 §J.3【セキュリティ最重要】ライブ観戦の STOMP 購読認可インターセプタ。
 *
 * <p>STOMP {@code SUBSCRIBE} フレームのうち <b>{@code /topic/matches/{matchId}/live} 宛先のみ</b>を
 * 認可対象とし、{@link MatchAccessService#canView}（F00 {@code MatchVisibilityResolver} へ委譲・03 §C.3.2）で
 * 可視性を検証する。可視性が無い者（他テナント・非公開試合の未認証者など）の購読は
 * {@link MessagingException} をスローして拒否する（ERROR フレーム返却・購読不成立）。</p>
 *
 * <h3>設計上の不変条件（07 §J.3）</h3>
 * <ul>
 *   <li><b>match live 宛先以外（chat / lobby / corkboard 等）は素通し</b>する。宛先プレフィックス判定で
 *       本機能のトピックに限定し、既存購読を壊さない（§J.3.2）。</li>
 *   <li><b>CONNECT フェイルオープンは是正しない</b>（既存 {@link com.mannschaft.app.config.WebSocketAuthChannelInterceptor}
 *       の挙動を変えない）。接続は緩いままでも、SUBSCRIBE 時点で {@code canView} を必ず通すため可視性の穴は生じない。</li>
 *   <li>認可の正準は {@code MatchAccessService.canView} → F00 へ委譲。独自 visibility 述語は書かない
 *       （メモリ教訓「可視性は必ず F00 ContentVisibilityResolver 経由」）。</li>
 *   <li>未認証（session userId=null）は {@code canView(null, matchId)} に委譲し、F00 の未ログイン可視性
 *       （PUBLIC 等）に従う＝<b>公開可視性の試合のみ</b>未ログイン観戦可（§J.3.1）。</li>
 * </ul>
 *
 * <p>登録は {@link com.mannschaft.app.config.WebSocketConfig} の inbound channel に
 * 認証インターセプタ（{@code WebSocketAuthChannelInterceptor}）の<b>後段</b>で行う
 * （CONNECT で確定した session userId を本インターセプタが参照するため・順序が重要）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.3 /
 * 03_permissions_and_recording_modes.md §C.8</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MatchLiveSubscriptionInterceptor implements ChannelInterceptor {

    /** CONNECT 時に {@code WebSocketAuthChannelInterceptor} が格納する session 属性キー。 */
    private static final String SESSION_USER_ID = "userId";

    /**
     * match live トピックの宛先パターン。{@code /topic/matches/{uuid}/live} のみにマッチする。
     * matchId 部分は UUID 形式に厳格化（不正な文字列はマッチさせず素通し＝本機能対象外として扱う）。
     */
    private static final Pattern MATCH_LIVE_DESTINATION = Pattern.compile(
            "^/topic/matches/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/live$");

    private final MatchAccessService matchAccessService;

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

        Matcher matcher = MATCH_LIVE_DESTINATION.matcher(destination);
        // match live 宛先以外（chat / lobby / corkboard 等）は認可対象外＝素通し（§J.3.2・既存topic非破壊）。
        if (!matcher.matches()) {
            return message;
        }

        UUID matchId;
        try {
            matchId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            // 正規表現を通過しているため通常到達しないが、念のため不正 matchId の購読は拒否する。
            throw new MessagingException("ライブ観戦の購読先 matchId が不正です: " + destination);
        }

        Long userId = resolveUserId(accessor);

        // F00 可視性を正準（MatchAccessService.canView → MatchVisibilityResolver）経由で検証する。
        // canView は親 matches をテナント取得してから判定するため、他テナント越境・IDOR も遮断される。
        if (!matchAccessService.canView(userId, matchId)) {
            log.debug("ライブ観戦の購読を拒否: userId={}, matchId={}, destination={}", userId, matchId, destination);
            // 購読を不成立にする（ERROR フレームが返り SUBSCRIBE は確立しない）。
            throw new MessagingException("ライブ観戦の購読権限がありません");
        }

        log.debug("ライブ観戦の購読を許可: userId={}, matchId={}", userId, matchId);
        return message;
    }

    /**
     * CONNECT 時に {@link com.mannschaft.app.config.WebSocketAuthChannelInterceptor} が
     * session 属性へ格納した userId を取り出す。未認証（属性なし）は {@code null} を返し、
     * {@code canView(null, ...)} の未ログイン可視性判定へ委譲する。
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
