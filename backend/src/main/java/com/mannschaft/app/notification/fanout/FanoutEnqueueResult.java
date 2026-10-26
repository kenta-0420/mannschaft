package com.mannschaft.app.notification.fanout;

import java.util.UUID;

/**
 * F01.2.1 §6.7: {@link NotificationFanoutJobService#enqueueInCurrentTransaction(FanoutEnqueueCommand)} の戻り値。
 *
 * <p>Service の公開 API に Entity を出さない規約（{@code ServiceApiEntityBoundaryArchTest}）に従い、
 * 親ジョブ行そのものではなく、呼び出し側が必要とする識別子とシャード状態だけを持つ。</p>
 *
 * @param jobId      登録済み（新規または既存）の親ジョブ行の ID
 * @param shardCount 親ジョブ行の {@code shard_count}（{@code 0}=未評価／{@code 1}=単一）
 */
public record FanoutEnqueueResult(UUID jobId, short shardCount) {
}
