package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;

import java.util.List;
import java.util.UUID;

/**
 * F01.2.1 §6.7: 呼び出し側トランザクションに参加して fan-out ジョブを enqueue するための引数一式。
 *
 * <p>既存の9引数版 {@link NotificationFanoutJobService#enqueueInCurrentTransaction(String, String, String,
 * UUID, Long, NotificationPriority, Long, String, Long)} は CONFIRMABLE_TARGETS 専用（文面行なし・
 * {@code action_url} なし・{@code shard_count=1} 固定・重複時は例外が伝播）であり変更しない。
 * 本 record を受ける新版は、文面の描画・{@code action_url}・シャードの扱いを持ち、ジョブ行と文面行を
 * 冪等 SQL で登録する。</p>
 *
 * <p>戻り値は Entity ではなく {@link FanoutEnqueueResult}（Service API の Entity 境界規約）。</p>
 *
 * @param scopeType         受信者解決の戦略キー（20文字以内）
 * @param scopeRef          多型スコープ参照
 * @param notificationType  通知種別
 * @param idempotencyKey    冪等キー（{@code source_event_uuid} に入る決定的な UUID）
 * @param organizationId    テナント（NULL 可）
 * @param priority          優先度（NULL は NORMAL 相当）
 * @param actorId           実行者ID（NULL 可）
 * @param sourceType        ソース種別（NULL 可）
 * @param sourceId          ソースID（NULL 可）
 * @param actionUrl         アクション URL（NULL 可）
 * @param includeSupporters 応援者を配信対象に含めるか
 * @param messageKind       文面テンプレート種別（NULL なら文面行を作らない）
 * @param messageArgs       文面の引数（利用者が書いた中身。NULL は引数なし）
 * @param shardMode         シャードの扱い
 */
public record FanoutEnqueueCommand(String scopeType,
                                   String scopeRef,
                                   String notificationType,
                                   UUID idempotencyKey,
                                   Long organizationId,
                                   NotificationPriority priority,
                                   Long actorId,
                                   String sourceType,
                                   Long sourceId,
                                   String actionUrl,
                                   boolean includeSupporters,
                                   FanoutMessageKind messageKind,
                                   List<String> messageArgs,
                                   ShardMode shardMode) {

    /** シャードの扱い。 */
    public enum ShardMode {
        /** {@code shard_count=1} 固定（受信者が数名の通知）。 */
        FIXED_SINGLE,
        /** {@code shard_count=0}（未評価）で登録し、Worker の {@code resolveAndSplitShards} に評価させる。 */
        AUTO
    }
}
