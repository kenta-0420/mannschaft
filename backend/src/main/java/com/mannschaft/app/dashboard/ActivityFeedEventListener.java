package com.mannschaft.app.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.dashboard.dto.ScheduleFeedDetail;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.dashboard.service.ActivitySummaryGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * アクティビティフィード書き込みイベントリスナー。
 * 各機能の Service が発行する ActivityEvent を非同期で受信し、activity_feed テーブルに INSERT する。
 * メインのトランザクションに影響させないよう、AFTER_COMMIT フェーズで @Async 実行する。
 * INSERT 失敗時はリトライせず WARN ログのみ出力する（30日で消えるデータのためコストに見合わない）。
 *
 * <p><strong>フィード洪水対策（F03.18 §5.4）</strong>: 同一操作者が同一予定に対して
 * 5分以内に連続して更新した場合、新規 INSERT せず直近1行へマージする（判定・実装は
 * 本リスナーに一本化する＝Service 層での事前確認は非同期レースを生むため不採用）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedEventListener {

    private final ActivityFeedRepository activityFeedRepository;
    private final ActivitySummaryGenerator summaryGenerator;

    /**
     * 業務ローカル時刻の壁時計（{@code ClockConfig#wallClock}）。
     *
     * <p>マージ窓の判定で比較する {@code ActivityFeedEntity#createdAt} は {@code @PrePersist} が
     * JVM 既定ゾーン基準の壁時計として書き込む値である。UTC 固定の既定 Clock
     * （{@code ClockConfig#utcClock}）で「今」を取ると、既定ゾーンが UTC でない環境
     * （本番・開発機ともに JST）で 5 分の窓がオフセット分ずれてマージ判定が壊れるため、
     * {@code ActivityFeedCleanupBatchService} と同じく壁時計 Bean を明示注入する。
     * 引数なし {@code LocalDateTime.now()} を使わないことで暗黙のゾーン依存も断つ
     * （番人 {@code DateTimeAndZoneGuardTest} / CMP-023）。テストでは固定 Clock を差し込める。</p>
     */
    @Qualifier("wallClock")
    private final Clock clock;

    /**
     * detail JSON の読み書き用 ObjectMapper。
     * 素の JSON ⇄ DTO 変換のみを行うため定数として保持する（ActivityFeedService と同方針）。
     */
    private static final ObjectMapper DETAIL_OBJECT_MAPPER = new ObjectMapper();

    /** マージ対象とみなす連続編集の時間窓（§5.4）。 */
    static final Duration MERGE_WINDOW = Duration.ofMinutes(5);

    /** マージ対象の活動種別（更新系のみ。作成・削除はマージしない）。 */
    private static final Set<ActivityType> MERGEABLE_TYPES =
            Set.of(ActivityType.SCHEDULE_UPDATED, ActivityType.SCHEDULE_RESCHEDULED);

    /**
     * アクティビティイベントを受信してフィードに書き込む。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。ダッシュボードのアクティビティフィード投入。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleActivityEvent(ActivityEvent event) {
        try {
            if (tryMergeIntoRecentRow(event)) {
                return;
            }

            String summary = summaryGenerator.generate(event.getActivityType());

            ActivityFeedEntity entity = ActivityFeedEntity.builder()
                    .scopeType(event.getScopeType())
                    .scopeId(event.getScopeId())
                    .actorId(event.getActorId())
                    .activityType(event.getActivityType())
                    .targetType(event.getTargetType())
                    .targetId(event.getTargetId())
                    .summary(summary)
                    .detail(event.getDetail())
                    .build();

            activityFeedRepository.save(entity);

            log.debug("アクティビティフィード書き込み完了 activityType={}, scopeType={}, scopeId={}, actorId={}",
                    event.getActivityType(), event.getScopeType(), event.getScopeId(), event.getActorId());
        } catch (Exception e) {
            log.warn("アクティビティフィード書き込み失敗 activityType={}, scopeType={}, scopeId={}, actorId={}, error={}",
                    event.getActivityType(), event.getScopeType(), event.getScopeId(), event.getActorId(), e.getMessage(), e);
            // リトライは行わない（30日で消えるデータのためコストに見合わない）
        }
    }

    /**
     * 直近5分以内の同一操作者・同一予定の更新行へマージする（§5.4）。
     *
     * <p>マージ条件（すべて満たす場合のみ）:</p>
     * <ul>
     *   <li>対象が SCHEDULE で、活動種別が SCHEDULE_UPDATED / SCHEDULE_RESCHEDULED</li>
     *   <li>同一 actorId・targetId の直近行が存在し、その種別も更新系である</li>
     *   <li>直近行の createdAt が現在時刻から {@link #MERGE_WINDOW} 以内</li>
     * </ul>
     *
     * <p>マージ時の規約: {@code createdAt} は据え置き（表示順＝初回操作時刻を維持）、
     * {@code fields} は「before は初回値・after は最新値」に畳み、種別は
     * UPDATED → RESCHEDULED の昇格のみ行う（降格しない）。</p>
     *
     * @return マージした場合 true（呼出元は新規 INSERT を行わない）
     */
    private boolean tryMergeIntoRecentRow(ActivityEvent event) {
        if (event.getTargetType() != TargetType.SCHEDULE
                || !MERGEABLE_TYPES.contains(event.getActivityType())
                || event.getTargetId() == null) {
            return false;
        }

        Optional<ActivityFeedEntity> recent = activityFeedRepository
                .findTopByActorIdAndTargetIdAndTargetTypeOrderByIdDesc(
                        event.getActorId(), event.getTargetId(), TargetType.SCHEDULE);
        if (recent.isEmpty()) {
            return false;
        }

        ActivityFeedEntity existing = recent.get();
        if (!MERGEABLE_TYPES.contains(existing.getActivityType())) {
            // 直近行が作成・削除の行なら別の編集セッションとして新規 INSERT する。
            return false;
        }
        if (existing.getCreatedAt() == null
                || existing.getCreatedAt().isBefore(LocalDateTime.now(clock).minus(MERGE_WINDOW))) {
            return false;
        }

        ActivityType mergedType = promoteActivityType(existing.getActivityType(), event.getActivityType());
        String mergedDetail = mergeDetail(existing.getDetail(), event.getDetail());

        ActivityFeedEntity merged = existing.toBuilder()
                .activityType(mergedType)
                .summary(summaryGenerator.generate(mergedType))
                .detail(mergedDetail)
                // createdAt は据え置く（§5.4。5分間の連続編集を1つの編集セッションとして扱う）。
                .build();

        activityFeedRepository.save(merged);
        log.debug("アクティビティフィードをマージ activityFeedId={}, activityType={}, actorId={}, targetId={}",
                existing.getId(), mergedType, event.getActorId(), event.getTargetId());
        return true;
    }

    /**
     * 種別の昇格（UPDATED → RESCHEDULED のみ。逆方向の降格は行わない）。
     */
    private static ActivityType promoteActivityType(ActivityType existing, ActivityType incoming) {
        if (existing == ActivityType.SCHEDULE_RESCHEDULED || incoming == ActivityType.SCHEDULE_RESCHEDULED) {
            return ActivityType.SCHEDULE_RESCHEDULED;
        }
        return existing;
    }

    /**
     * detail JSON をマージする。
     *
     * <p>フィールド名で突き合わせ、既に記録済みのフィールドは <strong>before を初回値のまま維持し
     * after のみ最新値へ差し替える</strong>（最初の値から最新の値への差分として1行にまとめる）。
     * 新規フィールドは末尾に追加する。{@code title}・{@code affectedCount} は最新値で上書きする。</p>
     *
     * <p>いずれかのパースに失敗した場合は握りつぶさず WARN を出し、
     * <strong>最新側の detail をそのまま採用</strong>する（フィード行そのものは失わない）。</p>
     */
    private String mergeDetail(String existingJson, String incomingJson) {
        if (incomingJson == null || incomingJson.isBlank()) {
            return existingJson;
        }
        if (existingJson == null || existingJson.isBlank()) {
            return incomingJson;
        }
        try {
            ScheduleFeedDetail existing = DETAIL_OBJECT_MAPPER.readValue(existingJson, ScheduleFeedDetail.class);
            ScheduleFeedDetail incoming = DETAIL_OBJECT_MAPPER.readValue(incomingJson, ScheduleFeedDetail.class);

            Map<String, ScheduleFeedDetail.FieldDiff> byField = new LinkedHashMap<>();
            for (ScheduleFeedDetail.FieldDiff diff : nullSafe(existing.fields())) {
                byField.put(diff.field(), diff);
            }
            for (ScheduleFeedDetail.FieldDiff diff : nullSafe(incoming.fields())) {
                ScheduleFeedDetail.FieldDiff prior = byField.get(diff.field());
                if (prior == null) {
                    byField.put(diff.field(), diff);
                } else {
                    // before は «初回値» を維持し、after のみ最新値へ差し替える。
                    byField.put(diff.field(), new ScheduleFeedDetail.FieldDiff(
                            diff.field(),
                            prior.before(),
                            diff.after(),
                            diff.changed() != null ? diff.changed() : prior.changed()));
                }
            }

            ScheduleFeedDetail merged = new ScheduleFeedDetail(
                    incoming.scheduleId() != null ? incoming.scheduleId() : existing.scheduleId(),
                    incoming.title() != null ? incoming.title() : existing.title(),
                    new ArrayList<>(byField.values()),
                    incoming.affectedCount() != null ? incoming.affectedCount() : existing.affectedCount());
            return DETAIL_OBJECT_MAPPER.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            log.warn("アクティビティフィード detail のマージに失敗（最新の detail を採用する） error={}", e.getMessage());
            return incomingJson;
        }
    }

    private static List<ScheduleFeedDetail.FieldDiff> nullSafe(List<ScheduleFeedDetail.FieldDiff> fields) {
        return fields == null ? List.of() : fields;
    }
}
