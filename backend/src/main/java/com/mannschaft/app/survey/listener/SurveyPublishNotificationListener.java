package com.mannschaft.app.survey.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.fanout.OrgFanoutRecipientSource;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.SurveyNotificationType;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.event.SurveyPublishedEvent;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * アンケート公開イベントを受信し、配信母集団へ公開通知（{@code SURVEY_CREATED}）を送信するリスナー。
 *
 * <p>設計書 F05.4 §1528 で {@code SURVEY_CREATED}＝「公開時にスコープ内全メンバーへ通知」が
 * enum 定義されていながら未発火だった既存乖離を、本リスナーで実装する。</p>
 *
 * <h2>fan-out 抜本改修 Wave-2: 組織スコープ×ALL は耐久ジョブへ移譲（AC-6）</h2>
 * <p>組織スコープ×ALL の配信母集団（直属 ∪ 配下 ACTIVE チーム）は数万規模になり得るため、
 * 受信者を同期展開せず {@link NotificationFanoutJobService#enqueue} で耐久 fan-out ジョブを
 * <b>1 件 enqueue</b> するだけ（O(1)）とし、実配信は裏ワーカー {@code NotificationFanoutWorker} が
 * ORG 受信者ソース（{@link OrgFanoutRecipientSource}）でキーセット・チャンク送り・クラッシュ再開可能に配信する。
 * 応援者トグル {@code includeSupporters} はジョブ列 {@code include_supporters} へ運搬し、母集団条件
 * （配下チーム展開・応援者除外・ACTIVE/未削除）はワーカー側の keyset クエリに閉じ込める。</p>
 *
 * <p>チームスコープ×ALL（配下展開なし）／TARGETED（{@code survey_targets} 明示列挙）は母集団が有界のため、
 * 従来どおり同期で {@link NotificationHelper#notifyAllPreAuthorized} に通知する（配信＝受信権統一）。</p>
 *
 * <h2>best-effort・冪等（Wave-1 {@code ShiftPublishedNotificationListener} に倣う）</h2>
 * <p>本リスナーは {@code AFTER_COMMIT} + {@code @Async("event-pool")} で走るため、公開の状態確定は既に確定済み。
 * enqueue／通知の失敗は内部で捕捉してログに留め、業務トランザクションを巻き込まない（例外を外へ伝播しない）。
 * 冪等キー {@code source_event_uuid} は「アンケート公開」という論理イベント（{@code surveyId × occurredAt}）から
 * 決定的に導出し、同一公開の二重発火は {@code uk_fanout_idempotency} で 1 ジョブに収束する。再公開は
 * {@code occurredAt} が更新されるため別キーとなり新ジョブが立つ（{@code surveyId} 単独による再通知の恒久抑止を避ける）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyPublishNotificationListener {

    private final UserRoleRepository userRoleRepository;
    private final SurveyTargetRepository surveyTargetRepository;
    private final NotificationHelper notificationHelper;
    private final NotificationFanoutJobService fanoutJobService;
    private final MessageSource messageSource;

    /**
     * アンケート公開イベントを受信して配信母集団へ公開通知を送信する。
     *
     * @param event アンケート公開イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。アンケート公開の通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurveyPublished(SurveyPublishedEvent event) {
        try {
            if (event.getDistributionMode() == DistributionMode.ALL
                    && "ORGANIZATION".equals(event.getScopeType())) {
                // 組織スコープ×ALL: 受信者を展開せず耐久 fan-out ジョブを 1 件 enqueue（O(1)）。
                // 配下チーム展開・応援者トグル適用・ACTIVE/未削除の母集団条件はワーカー（OrgFanoutRecipientSource）が
                // keyset クエリで処理する。
                // Issue #2871 で解消: ジョブは描画済み文字列ではなく「文面種別＋利用者が書いた中身」を
                // 運ぶようになった。enqueue が 6 配信ロケールぶんの文面を描画して子表に保存し、
                // ワーカーが受信者の locale で選んで配る。翻訳するのは枠だけで、アンケート名は
                // 利用者が書いた文字列としてそのまま差し込む（翻訳も改変もしない）。
                fanoutJobService.enqueue(
                        OrgFanoutRecipientSource.SCOPE_TYPE,               // 戦略キー: ORGANIZATION
                        String.valueOf(event.getScopeId()),                // scope_ref: 組織 ID 文字列
                        SurveyNotificationType.SURVEY_CREATED.name(),
                        sourceEventUuid(event.getSurveyId(), event.occurredAt()), // 冪等キー: 公開イベント（surveyId×occurredAt）
                        event.getScopeId(),                                // organizationId: テナント（組織 ID）
                        FanoutMessageKind.SURVEY_PUBLISHED,
                        new String[]{event.getTitle()},    // 利用者が書いたアンケート名（翻訳しない）
                        NotificationPriority.NORMAL,
                        "SURVEY", event.getSurveyId(),
                        "/surveys/" + event.getSurveyId(),
                        event.getActorId(),
                        event.isIncludeSupporters());                      // 応援者トグルをジョブへ運搬
                log.info("アンケート公開通知を enqueue: surveyId={} orgId={} includeSupporters={}（受信者展開はワーカーへ移譲）",
                        event.getSurveyId(), event.getScopeId(), event.isIncludeSupporters());
                return;
            }

            // チームスコープ×ALL（配下展開なし）／TARGETED: 母集団が有界のため従来どおり同期通知する。
            List<Long> recipients = resolveRecipients(event);
            if (recipients.isEmpty()) {
                return;
            }
            NotificationScopeType notifScope = "TEAM".equals(event.getScopeType())
                    ? NotificationScopeType.TEAM
                    : NotificationScopeType.ORGANIZATION;
            // 配信＝受信権 統一（関所(1)通知 / E: ResultsVisibility 誤用是正）:
            // recipients は resolveRecipients が配信母集団として確定済みのため、canView 絞り込み
            // （SURVEY の結果閲覧 ResultsVisibility 軸を含む）を通さない
            // notifyAllPreAuthorizedLocalized（Issue #2715 CMP-055 ロットC-5で追加）を使う。
            notificationHelper.notifyAllPreAuthorizedLocalized(
                    recipients,
                    SurveyNotificationType.SURVEY_CREATED.name(),
                    "SURVEY",
                    event.getSurveyId(),
                    notifScope,
                    event.getScopeId(),
                    "/surveys/" + event.getSurveyId(),
                    event.getActorId(),
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.survey.published.title", null,
                                    "新しいアンケートが公開されました", locale),
                            messageSource.getMessage(
                                    "notification.survey.published.body",
                                    new Object[]{event.getTitle()},
                                    "「" + event.getTitle() + "」が公開されました。回答にご協力ください。",
                                    locale)));
            log.info("アンケート公開通知送信: surveyId={}, recipientCount={}",
                    event.getSurveyId(), recipients.size());
        } catch (Exception e) {
            log.warn("アンケート公開通知の送信に失敗: surveyId={}", event.getSurveyId(), e);
        }
    }

    /**
     * 同期通知の配信母集団（チームスコープ×ALL / TARGETED）を解決する。
     * 組織スコープ×ALL は {@link #onSurveyPublished} が耐久ジョブへ移譲するため本メソッドには到達しない。
     *
     * @param event アンケート公開イベント
     * @return 配信対象ユーザーIDリスト
     */
    private List<Long> resolveRecipients(SurveyPublishedEvent event) {
        if (event.getDistributionMode() == DistributionMode.ALL) {
            // チームスコープ×ALL（および COMMITTEE 等）: 配下展開なし・従来挙動を維持
            return userRoleRepository.findUserIdsByScope(event.getScopeType(), event.getScopeId());
        }
        // TARGETED: survey_targets が母集団
        return surveyTargetRepository.findBySurveyId(event.getSurveyId()).stream()
                .map(SurveyTargetEntity::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 「アンケート公開」という論理イベントから冪等キー UUID を決定的に導出する。
     *
     * <p>{@code surveyId × occurredAt}（公開イベント発生時刻）で構成する。同一 AFTER_COMMIT の二重発火は
     * 同一 {@code occurredAt} ゆえ同一 UUID となり {@code uk_fanout_idempotency} で 1 ジョブに収束する。
     * 再公開は {@code occurredAt} が更新されるため別 UUID となり新ジョブが立つ（{@code surveyId} 単独だと
     * 再公開通知が恒久抑止される回帰を防ぐ）。</p>
     */
    private static UUID sourceEventUuid(long surveyId, LocalDateTime occurredAt) {
        String seed = "SURVEY_CREATED_PUBLISH:" + surveyId + ":"
                + (occurredAt == null ? "-" : String.valueOf(occurredAt.toInstant(ZoneOffset.UTC).toEpochMilli()));
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
