package com.mannschaft.app.reservation.ws;

import com.mannschaft.app.common.AccessControlService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F03.4+ 臨時休業「確認状況」トピックの STOMP 購読認可インターセプタ。
 *
 * <p>STOMP {@code SUBSCRIBE} フレームのうち
 * <b>{@code /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations} 宛先のみ</b>を
 * 認可対象とし、<b>当該チームの厳格 ADMIN（{@code ADMIN} ロール）であること</b>を
 * {@link AccessControlService#isAdmin}（認可の正準サービス）へ委譲して検証する。
 * ADMIN でない者（非メンバー・MEMBER・SUPPORTER・DEPUTY_ADMIN・他チーム ADMIN・未認証）の購読は
 * {@link MessagingException} をスローして拒否する（ERROR フレーム返却・購読不成立）。</p>
 *
 * <h3>設計上の不変条件</h3>
 * <ul>
 *   <li><b>確認状況トピック以外（chat / lobby / match live / corkboard 等）は素通し</b>する。
 *       宛先パターン判定で本機能のトピックに限定し、既存購読を壊さない。</li>
 *   <li>認可の正準は {@link AccessControlService#isAdmin}。独自 visibility 述語・役割 gate は書かない
 *       （メモリ教訓「可視性は必ず F00 / 正準サービス経由」「独自述語は漏洩源」）。</li>
 *   <li>teamId は宛先パターンから抽出し、{@code isAdmin(userId, teamId, "TEAM")} で
 *       <b>その teamId に対する ADMIN ロール</b>を判定するため、他チーム ADMIN による越境（IDOR）も遮断される。</li>
 *   <li>未認証（session userId=null）は ADMIN ではあり得ないため購読を拒否する
 *       （確認状況一覧は送信者＝チーム管理者専用のため、未ログイン観覧は許容しない）。</li>
 * </ul>
 *
 * <p>登録は {@link com.mannschaft.app.config.WebSocketConfig} の inbound channel に
 * 認証インターセプタ（{@code WebSocketAuthChannelInterceptor}）の<b>後段</b>で行う
 * （CONNECT で確定した session userId を本インターセプタが参照するため・順序が重要）。</p>
 *
 * <p>配信トピック契約（配信側と一字一句一致）:
 * {@code /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations}
 * （{@code teamId} / {@code closureId} はいずれも数値）。</p>
 */
@Component("emergencyClosureSubscriptionInterceptor")
@RequiredArgsConstructor
@Slf4j
public class EmergencyClosureSubscriptionInterceptor implements ChannelInterceptor {

    /** CONNECT 時に {@code WebSocketAuthChannelInterceptor} が格納する session 属性キー。 */
    private static final String SESSION_USER_ID = "userId";

    /** チームスコープを表す scopeType 文字列（{@link AccessControlService} の規約）。 */
    private static final String SCOPE_TEAM = "TEAM";

    /**
     * 臨時休業「確認状況」トピックの宛先パターン。
     * {@code /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations} のみにマッチする。
     * teamId / closureId はいずれも数値（{@code \d+}）。それ以外の文字列はマッチさせず素通し（本機能対象外）。
     */
    private static final Pattern CONFIRMATIONS_DESTINATION = Pattern.compile(
            "^/topic/teams/(\\d+)/emergency-closures/(\\d+)/confirmations$");

    private final AccessControlService accessControlService;

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

        Matcher matcher = CONFIRMATIONS_DESTINATION.matcher(destination);
        // 確認状況トピック以外（chat / lobby / match live 等）は認可対象外＝素通し（既存topic非破壊）。
        if (!matcher.matches()) {
            return message;
        }

        long teamId;
        try {
            teamId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            // 正規表現を通過しているため通常到達しない（数値オーバーフロー時のみ）。安全側に倒して拒否する。
            throw new MessagingException("臨時休業の購読先 teamId が不正です: " + destination);
        }

        Long userId = resolveUserId(accessor);

        // 未認証は ADMIN ではあり得ないため拒否（確認状況一覧はチーム管理者専用）。
        if (userId == null) {
            log.debug("臨時休業確認状況の購読を拒否（未認証）: destination={}", destination);
            throw new MessagingException("臨時休業確認状況の購読権限がありません");
        }

        // 認可の正準（AccessControlService.isAdmin）へ委譲。
        // teamId に対する厳格 ADMIN のみ許可するため、他チーム ADMIN の越境（IDOR）も遮断される。
        if (!accessControlService.isAdmin(userId, teamId, SCOPE_TEAM)) {
            log.debug("臨時休業確認状況の購読を拒否: userId={}, teamId={}, destination={}",
                    userId, teamId, destination);
            // 購読を不成立にする（ERROR フレームが返り SUBSCRIBE は確立しない）。
            throw new MessagingException("臨時休業確認状況の購読権限がありません");
        }

        log.debug("臨時休業確認状況の購読を許可: userId={}, teamId={}", userId, teamId);
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
