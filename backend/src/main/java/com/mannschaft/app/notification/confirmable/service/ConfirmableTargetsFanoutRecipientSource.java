package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientRowMapper;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先ターゲット展開（軍議第8版確定稿 §3.2・§8.1）。
 *
 * <p>{@code confirmable_notification_targets} を読み、次の和集合をキーセット方式でページングする。
 * <ul>
 *   <li>ORGANIZATION(id): {@code OrgFanoutRecipientSource} と同じ定義（再帰CTE。直属メンバー＋
 *       ACTIVE 所属チームのメンバー）</li>
 *   <li>TEAM(id): そのチームの在籍メンバー（memberships の left_at IS NULL と user_roles の team_id
 *       の和集合。生存ユーザーに限る）</li>
 * </ul>
 * 純 SUPPORTER は除外し、一意化し、送信者本人を除外する（AC-1〜7）。
 *
 * <p>§8.1 により、展開はワーカーがチャンクを処理する<b>その時点</b>の所属関係で行う（配下から
 * 外れたターゲットは以後0人として扱い、エラーにしない）。1万件超のシャード分割には対応せず
 * shard_count=1 固定（マスター裁可）。</p>
 */
@Component
@RequiredArgsConstructor
public class ConfirmableTargetsFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー。scope_ref は確認通知IDを文字列化したもの。 */
    public static final String SCOPE_TYPE = "CONFIRMABLE_TARGETS";

    /** OrgFanoutRecipientSource と同じサイクル防止上限。 */
    static final int MAX_ORG_DESCENDANT_DEPTH = 32;

    private final ConfirmableNotificationTargetRepository targetRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
        if (!request.isSingleShard()) {
            throw new UnsupportedOperationException(
                    "CMP-260920-1040: CONFIRMABLE_TARGETS はシャード分割に対応しない（shard_count=1 固定）");
        }
        long notificationId = Long.parseLong(request.scopeRef());
        int limit = request.limit();
        return FanoutRecipientRowMapper.toRecipients(
                targetRepository.findConfirmableTargetRecipientsKeyset(
                        notificationId, request.includeSupporters(), MAX_ORG_DESCENDANT_DEPTH,
                        request.cursorSubjectId(), limit, PageRequest.of(0, limit)));
    }

    @Override
    public long countRecipients(String scopeRef, boolean includeSupporters) {
        long notificationId = Long.parseLong(scopeRef);
        return targetRepository.countConfirmableTargetRecipients(
                notificationId, includeSupporters, MAX_ORG_DESCENDANT_DEPTH);
    }
}
