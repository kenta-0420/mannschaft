package com.mannschaft.app.todo.event;

/**
 * マイルストーンがアンロック（前マイルストーン達成 or 強制アンロック）された際のイベント。
 *
 * <p>F04.3 プッシュ通知・WebSocket 配信のトリガーとして利用される。
 * Spring {@link org.springframework.context.ApplicationEventPublisher} 経由で発行する。</p>
 *
 * @param projectId              対象プロジェクト ID
 * @param milestoneId            アンロックされたマイルストーン ID
 * @param triggeredByMilestoneId アンロックの起点となった前マイルストーン ID
 *                               （強制アンロック時は NULL）
 * @param isForced               true: 強制アンロック / false: 前マイルストーン達成による自動アンロック
 */
public record MilestoneUnlockedEvent(
        Long projectId,
        Long milestoneId,
        Long triggeredByMilestoneId,
        boolean isForced
) {
}
