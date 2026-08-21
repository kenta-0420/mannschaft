package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.village.fanout.VillageFanoutRecipientSource;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * F17.2 Wave2 ① 行事→村フィード自動還流サービス（設計書 §3）。
 *
 * <p>行事が「立った／近づいた／確定した／始まった」ときに、村タイムラインへ<b>システム名義の投稿</b>
 * （{@link TimelinePostService#createSystemVillagePost}）を作り、村の現役メンバーへ通知する。</p>
 *
 * <h2>分離・best-effort（原則5・設計書 §3.3）</h2>
 * <p>本サービスの発火は行事の状態遷移トランザクションの<b>外</b>で呼ばれる前提であり
 * （サービス経由は {@code afterCommit}・バッチ経由はループ本体でのコミット後）、<b>例外を外へ伝播しない</b>。
 * 自動投稿・通知の失敗が行事の状態確定を巻き戻さないことを保証するため、内部で捕捉してログに残す
 * （握り潰しではなく、状態遷移とは独立した best-effort 副作用としての設計上の分離）。</p>
 *
 * <h2>冪等（設計書 §3.7）</h2>
 * <p>{@code (scope_village_id, system_post_type, source_event_uuid)} の存在チェックで、
 * 繰り返しバッチ（EVENT_UPCOMING）でも同一行事へ二重投稿しない。通知側は fan-out 耐久ジョブの
 * ユニークキー {@code uk_fanout_idempotency} でも冪等が効く（二重の安全網）。</p>
 *
 * <h2>fan-out 抜本改修 P2: 受信者展開はワーカーへ移譲</h2>
 * <p>通知の受信者ページングは本サービスでは行わず、{@link NotificationFanoutJobService#enqueue} で耐久ジョブを
 * <b>1 件 enqueue</b> するだけ（O(1)）。50 万人規模の受信者展開は裏ワーカー
 * {@code NotificationFanoutWorker} がチャンク単位・クラッシュ再開可能に配信する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageEventFeedRefluxService {

    // Issue #2871: 通知本文の切り詰めは FanoutMessageRenderer がコードポイント境界で行うようになったため、
    // ここにあった NOTIFICATION_BODY_LIMIT（素の substring による切り詰め）は撤去した。
    // 素の substring はサロゲートペアの途中で切って壊れた文字を作りうる。
    private static final String SOURCE_TYPE = "VILLAGE_EVENT";

    private final TimelinePostService timelinePostService;
    private final NotificationFanoutJobService fanoutJobService;
    private final AuditLogService auditLogService;

    /**
     * 村行事の還流（システム投稿＋通知）を best-effort で発火する。冪等・例外は外へ伝播しない。
     *
     * @param villageId       対象村 UUID
     * @param type            通知種別（{@code system_post_type} にも {@code .name()} で格納）
     * @param sourceEventUuid 対象行事 UUID（歳時記/祭/寄合の id）
     * @param eventTitle      行事の表題（本文組み立て用）
     * @param actionUrl       通知タップ先（村・行事 UUID を含む相対 URL）
     */
    public void publish(UUID villageId, VillageEventNotificationType type, UUID sourceEventUuid,
                        String eventTitle, String actionUrl) {
        if (villageId == null || type == null || sourceEventUuid == null) {
            return;
        }
        boolean created = false;
        try {
            // 冪等: 既に当該行事×種別のシステム投稿があれば投稿・通知とも行わない（EVENT_UPCOMING の二重送信防止）。
            if (timelinePostService.systemVillagePostExists(villageId, type, sourceEventUuid)) {
                return;
            }
            String content = buildContent(type, eventTitle);
            timelinePostService.createSystemVillagePost(villageId, type, sourceEventUuid, content);
            created = true;
            // §16.2 監査: システム名義投稿の作成を記録（actorId=null・システム発火）。
            auditLogService.record(
                    AuditEventType.VILLAGE_EVENT_SYSTEM_POSTED.name(),
                    null, null, null, null, null, null, null,
                    "{\"villageId\":\"" + villageId + "\",\"type\":\"" + type.name()
                            + "\",\"sourceEventUuid\":\"" + sourceEventUuid + "\"}");
        } catch (Exception e) {
            // best-effort: 状態遷移は既に確定済み。自動投稿失敗は還流の欠落に留め、状態は巻き戻さない。
            log.error("村行事の自動投稿に失敗: villageId={} type={} sourceEventUuid={}",
                    villageId, type, sourceEventUuid, e);
        }

        if (!created) {
            return;
        }

        // 通知も best-effort（投稿と独立に捕捉）。ニュースレター §7 前例に倣い sourceId=null・通知スコープは SYSTEM。
        // fan-out 抜本改修 P2: 受信者を展開せず耐久ジョブを 1 件 enqueue するだけ（O(1)）。
        // 重い受信者ページング＋バルク INSERT はワーカーへ移譲し、クラッシュ再開可能に配信する。
        try {
            // Issue #2871: 通知だけを i18n 化する。ここでは描画済み文字列を渡さず「文面種別＋行事名」を渡し、
            // enqueue が 6 配信ロケールぶんの文面を描画して子表へ保存する。
            //
            // ⚠ タイムライン投稿（上の createSystemVillagePost）とは<b>意図的に別経路</b>である。
            //   投稿は村人全員が同じ 1 本を読む「村の掲示板」であり、受信者ごとに言語を変えることが
            //   そもそもできない（1 行のレコードに 1 つの本文しか無い）。したがって投稿は従来どおり
            //   buildContent の日本語リテラルを使い続け、通知だけを受信者ごとに描画する。
            //   両者を 1 つの関数から作ると「投稿を翻訳しようとして全村人の掲示板がバラバラになる」か
            //   「通知の翻訳を諦める」かのどちらかに倒れるため、経路を分けたままにしてある。
            //
            //   切り詰め（本文 1000）も FanoutMessageRenderer 側でコードポイント境界で行うため、
            //   ここでの substring による切り詰めは不要になった（サロゲートペアを割らない）。
            fanoutJobService.enqueue(
                    VillageFanoutRecipientSource.SCOPE_TYPE,   // 戦略キー: VILLAGE
                    villageId.toString(),                       // scope_ref: 村 UUID 文字列
                    type.name(),
                    sourceEventUuid,
                    null,                                        // organizationId: 村行事は org 非依存
                    messageKindOf(type),
                    new String[]{eventTitle == null ? "" : eventTitle},  // 利用者が書いた行事名（翻訳しない）
                    NotificationPriority.NORMAL,
                    SOURCE_TYPE, null,
                    actionUrl, null);
            log.info("村行事の還流通知を enqueue: villageId={} type={} sourceEventUuid={}（受信者展開はワーカーへ移譲）",
                    villageId, type, sourceEventUuid);
        } catch (Exception e) {
            log.error("村行事の通知 enqueue に失敗: villageId={} type={} sourceEventUuid={}",
                    villageId, type, sourceEventUuid, e);
        }
    }

    /**
     * 種別ごとの<b>タイムライン投稿本文</b>（村の掲示板に 1 本だけ積まれるシステム投稿）。
     *
     * <p><b>これは通知の本文ではない。</b> 投稿は村人全員が同じ 1 レコードを読むため、
     * 受信者ごとに言語を変えるという概念が成立しない（1 行に 1 つの本文しか持てない）。
     * よってここは Issue #2871 の i18n 化の対象外で、従来どおり日本語のリテラルを組み立てる。
     * 受信者ごとに言語が変わるのは通知側だけであり、そちらは {@link #messageKindOf} で
     * 文面種別に写して fan-out の enqueue へ渡している。</p>
     */
    private String buildContent(VillageEventNotificationType type, String eventTitle) {
        String title = eventTitle == null ? "" : eventTitle;
        return switch (type) {
            case EVENT_CREATED -> "新しい行事「" + title + "」が追加されました";
            case EVENT_UPCOMING -> "明日「" + title + "」が開催されます";
            case MEETUP_CONFIRMED -> "寄合「" + title + "」の日程が決まりました";
            case FESTIVAL_STARTED -> "お祭り「" + title + "」が始まりました";
        };
    }

    /**
     * 村行事の通知種別を fan-out の文面種別へ写す（Issue #2871）。
     *
     * <p>4 分岐を「引数」ではなく「別のキー」で表すのは、行事追加 / 明日開催 / 寄合日程確定 / 祭り開始が
     * 語順・文意・助詞まで異なる別テンプレートだからである。種別を引数として properties へ渡すと
     * properties 側に分岐ロジックを持ち込むことになる。引数は行事名ただ 1 つに保つ。</p>
     */
    private static com.mannschaft.app.notification.fanout.FanoutMessageKind messageKindOf(
            VillageEventNotificationType type) {
        return switch (type) {
            case EVENT_CREATED -> com.mannschaft.app.notification.fanout.FanoutMessageKind.VILLAGE_EVENT_ADDED;
            case EVENT_UPCOMING -> com.mannschaft.app.notification.fanout.FanoutMessageKind.VILLAGE_EVENT_TOMORROW;
            case MEETUP_CONFIRMED ->
                    com.mannschaft.app.notification.fanout.FanoutMessageKind.VILLAGE_MEETING_CONFIRMED;
            case FESTIVAL_STARTED ->
                    com.mannschaft.app.notification.fanout.FanoutMessageKind.VILLAGE_FESTIVAL_STARTED;
        };
    }
}
