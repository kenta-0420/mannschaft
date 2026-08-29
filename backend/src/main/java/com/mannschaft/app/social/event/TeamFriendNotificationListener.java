package com.mannschaft.app.social.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.team.service.TeamService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * フレンドチーム成立／解除の通知配送リスナー（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code TeamFriendsService#follow} / {@code #unfollow} の業務トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>複数受信者</b>の金型として
 * ロットA の {@code EventAdvanceNoticeNotificationListener} と同型（受信者リストの解決は全体で1回・
 * 外側 try、受信者ごとに組み立て＋配送を内側 try で隔離）。</p>
 *
 * <h2>是正前の欠陥（Codex 検分 PR #2861 P1 の自認箇所）</h2>
 * <p>是正前は {@code follow} / {@code unfollow} の {@code @Transactional} 内で
 * {@code notificationHelper.notifyAllLocalized} を呼んでいた。受信者 locale の一括解決
 * （{@code UserLocaleCache#getLocales}・DB 参照）と可視性フィルタは個別 {@code try} の<b>外側</b>にあり、
 * ここが {@code DataAccessException} を投げると rollback-only が立って
 * <b>フレンド成立／解除（{@code team_friends} の INSERT / DELETE）ごと巻き戻っていた</b>。
 * 是正前のクラスコメントは「隔離 try を足しても救えない」と自認したうえで根治を #2834 に委ねていた。
 * 本リスナーがその根治にあたる。</p>
 *
 * <h2>削除済み source を参照しないことの確認（{@code unfollow} が危険な理由と、その結論）</h2>
 * <p>{@code unfollow} は {@code team_friends} 行を<b>物理削除</b>するため、{@code AFTER_COMMIT} 時点で
 * {@code sourceId=teamFriendId} は既に存在しない行を指す。ただし {@code sourceType=TEAM_FRIEND} は
 * {@code NotificationSourceTypeMapper} に<b>未登録</b>であり fail-soft で visibility ガードの対象外
 * （{@code ContentVisibilityChecker} は素通しする）。よってコミット後発火にしても
 * {@code FRIEND_DISSOLVED} が静かに deny されることはない。あわせて {@code actionUrl} は削除された
 * フレンド関係ではなく生存しているチームのフレンド一覧（{@code /teams/&#123;teamId&#125;/friends}）を
 * 指しており、遷移先も壊れない（是正前と同じ URL を維持している）。
 * チーム名の解決に使う {@code teams} 行も {@code unfollow} では削除されない。</p>
 *
 * <h2>配信面の等価性</h2>
 * <p>是正前の {@code notificationHelper.notifyAllLocalized} も create + dispatch であり、
 * {@link NotificationDeliveryRunner#sendOne} への置換で Push/WebSocket の有無は変わらない。</p>
 *
 * <h2>D-5: 越境アクセスは Repository ではなく Service 経由</h2>
 * <p>チーム名は {@code team} ドメインの {@code TeamRepository} ではなく
 * {@link TeamService#getNamesByIds}、両チーム ADMIN は {@code role} ドメインの
 * {@code UserRoleRepository} ではなく {@link RoleService#getUserIdsByTeamIdAndRoleName} を使う
 * （{@code CrossDomainRepositoryDependencyArchTest} D-5）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamFriendNotificationListener {

    /** ADMIN ロール名（是正前の {@code TeamFriendsService} 定数と同値）。 */
    private static final String ROLE_ADMIN = "ADMIN";

    /** チーム名が解決できない場合の既定表示（是正前の {@code orElse("チーム")} と同値）。 */
    private static final String DEFAULT_TEAM_NAME = "チーム";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final TeamService teamService;
    private final RoleService roleService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "チーム間フレンド（フォロー）は棚卸し台帳で beta=コア・gate_key 未発行の常時提供機能であり、付随通知だけを止める停止条件が存在しないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamFriendNotification(TeamFriendNotificationEvent event) {
        // 受信者リスト・チーム名の解決は全体で1回。ここが失敗したら誰にも送れない。
        NotificationContext ctx;
        try {
            ctx = resolveContext(event);
        } catch (Exception e) {
            log.error("フレンドチーム通知の受信者解決に失敗しました: kind={}, teamId={}, targetTeamId={}, teamFriendId={}",
                    event.kind(), event.teamId(), event.targetTeamId(), event.teamFriendId(), e);
            return;
        }

        // 自チーム ADMIN には相手チーム名を、相手チーム ADMIN には自チーム名を伝える（是正前と同じ）。
        Counters counters = new Counters();
        deliver(event, ctx, ctx.selfAdminIds(), event.teamId(), ctx.targetTeamName(), counters);
        deliver(event, ctx, ctx.targetAdminIds(), event.targetTeamId(), ctx.selfTeamName(), counters);

        // 集計ログのレベルは個別ログと揃える。deny は正常系なので WARN、例外が1件でもあれば ERROR。
        if (counters.failed > 0 || counters.denied > 0) {
            String summary = "フレンドチーム通知一括配送の結果: kind={}, teamId={}, targetTeamId={}, "
                    + "teamFriendId={}, total={}, failed={}, denied={}, firstFailedUserId={}";
            int total = ctx.selfAdminIds().size() + ctx.targetAdminIds().size();
            if (counters.failed > 0) {
                log.error(summary, event.kind(), event.teamId(), event.targetTeamId(), event.teamFriendId(),
                        total, counters.failed, counters.denied, counters.firstFailedUserId);
            } else {
                log.warn(summary, event.kind(), event.teamId(), event.targetTeamId(), event.teamFriendId(),
                        total, counters.failed, counters.denied, counters.firstFailedUserId);
            }
        }
    }

    /**
     * 受信者リスト・チーム名の解決（全体で1回）。ここが失敗したら誰にも送れないため、
     * 呼び出し元は一括で諦める。locale のバルク解決だけは失敗しても既定 locale で継続できるため
     * 内側で握る（ロットA と同じ扱い）。
     */
    private NotificationContext resolveContext(TeamFriendNotificationEvent event) {
        Map<Long, String> teamNames = teamService.getNamesByIds(List.of(event.teamId(), event.targetTeamId()));
        String selfTeamName = teamNames.getOrDefault(event.teamId(), DEFAULT_TEAM_NAME);
        String targetTeamName = teamNames.getOrDefault(event.targetTeamId(), DEFAULT_TEAM_NAME);

        List<Long> selfAdminIds = roleService.getUserIdsByTeamIdAndRoleName(event.teamId(), ROLE_ADMIN);
        List<Long> targetAdminIds = roleService.getUserIdsByTeamIdAndRoleName(event.targetTeamId(), ROLE_ADMIN);
        selfAdminIds = (selfAdminIds != null) ? selfAdminIds : List.of();
        targetAdminIds = (targetAdminIds != null) ? targetAdminIds : List.of();

        Map<Long, String> locales;
        try {
            List<Long> all = new ArrayList<>(selfAdminIds);
            all.addAll(targetAdminIds);
            locales = all.isEmpty() ? Map.of() : userLocaleCache.getLocales(all);
        } catch (Exception e) {
            log.warn("フレンドチーム通知の locale 一括解決に失敗（既定 locale で継続）: teamFriendId={}, error={}",
                    event.teamFriendId(), e.getMessage());
            locales = Map.of();
        }

        return new NotificationContext(selfTeamName, targetTeamName, selfAdminIds, targetAdminIds, locales);
    }

    /** 1グループぶんの配送。受信者ごとに組み立て＋送信を内側 try で隔離する。 */
    private void deliver(TeamFriendNotificationEvent event, NotificationContext ctx,
                         List<Long> recipientIds, Long scopeTeamId, String partnerTeamName,
                         Counters counters) {
        for (Long recipientUserId : recipientIds) {
            try {
                NotificationDeliveryRequest request = buildRequest(
                        recipientUserId, event, scopeTeamId, partnerTeamName,
                        Locale.forLanguageTag(ctx.locales().getOrDefault(recipientUserId, "ja")));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    counters.denied++;
                    log.warn("フレンドチーム通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                counters.failed++;
                if (counters.firstFailedUserId == null) {
                    counters.firstFailedUserId = recipientUserId;
                }
                log.error("フレンドチーム通知の配送に失敗しました: "
                                + "recipientUserId={}, kind={}, teamId={}, targetTeamId={}, teamFriendId={}",
                        recipientUserId, event.kind(), event.teamId(), event.targetTeamId(),
                        event.teamFriendId(), e);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(Long recipientUserId, TeamFriendNotificationEvent event,
                                                    Long scopeTeamId, String partnerTeamName, Locale locale) {
        boolean established = event.kind() == TeamFriendNotificationEvent.Kind.ESTABLISHED;
        String title = messageSource.getMessage(
                established
                        ? "notification.social.teamFriend.established.title"
                        : "notification.social.teamFriend.dissolved.title",
                new Object[]{partnerTeamName},
                established
                        ? partnerTeamName + "とフレンドチームになりました"
                        : partnerTeamName + "とのフレンドチーム関係が解除されました",
                locale);
        String body = messageSource.getMessage(
                established
                        ? "notification.social.teamFriend.established.body"
                        : "notification.social.teamFriend.dissolved.body",
                new Object[]{partnerTeamName},
                established
                        ? partnerTeamName + "との相互フォローが成立し、フレンドチームになりました"
                        : partnerTeamName + "とのフレンドチーム関係が解除されました",
                locale);

        return new NotificationDeliveryRequest(
                recipientUserId,
                established ? "FRIEND_ESTABLISHED" : "FRIEND_DISSOLVED",
                NotificationPriority.NORMAL,
                title,
                body,
                "TEAM_FRIEND",
                event.teamFriendId(),
                NotificationScopeType.FRIEND_TEAM,
                scopeTeamId,
                "/teams/" + scopeTeamId + "/friends",
                event.actorId());
    }

    /**
     * 受信者リスト・チーム名の解決結果（全体で1回だけ算出する共通情報）。
     *
     * @param selfTeamName   自チーム名
     * @param targetTeamName 相手チーム名
     * @param selfAdminIds   自チームの ADMIN ユーザーID一覧
     * @param targetAdminIds 相手チームの ADMIN ユーザーID一覧
     * @param locales        受信者 locale のバルク解決結果
     */
    private record NotificationContext(
            String selfTeamName,
            String targetTeamName,
            List<Long> selfAdminIds,
            List<Long> targetAdminIds,
            Map<Long, String> locales) {
    }

    /** 2グループ横断の集計カウンタ。 */
    private static final class Counters {
        private int denied;
        private int failed;
        private Long firstFailedUserId;
    }
}
