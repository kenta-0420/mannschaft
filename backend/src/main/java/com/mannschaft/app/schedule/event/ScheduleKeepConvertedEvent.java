package com.mannschaft.app.schedule.event;

import java.util.UUID;

/**
 * F03.17 キープ変換の通知配送要求イベント（Issue #2990 / L8）。
 *
 * <p>{@code ScheduleKeepService#convert} の業務トランザクション（キープの SCHEDULED 化＋
 * 変換先予定の INSERT）の内側で publish し、
 * {@code ScheduleKeepConvertedNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <h2>是正前は何が巻き戻っていたか（try/catch と REQUIRES_NEW では塞げていなかった）</h2>
 * <p>是正前も「通知の永続化だけ」は {@code ScheduleKeepNotificationPublisher}
 * （{@code REQUIRES_NEW}）へ逃がしてあり、クラス javadoc は<b>それで変換は守られると宣言していた</b>。
 * しかし {@code ScheduleKeepNotificationService#notifyConverted} は同じメソッドの中で、
 * TEAM スコープのとき {@code NotificationFanoutJobService#enqueue}（fan-out 親ジョブ 1 行の INSERT）を
 * <b>外側の業務トランザクションのまま</b>実行していた。この INSERT が落ちるとトランザクションに
 * rollback-only が立ち、{@code ScheduleKeepService} 側の {@code catch} が
 * 「キープ変換通知の発行に失敗しました（変換自体は成立）」と<b>嘘のログ</b>を残したうえで、
 * 直後のコミットが {@code UnexpectedRollbackException} になって<b>変換ごと失われていた</b>。
 * 可視性判定（{@code contentVisibilityChecker.canViewUuid}）も同じく外側 TX の中で SQL を引いている。</p>
 *
 * <p>是正後は通知の解決・組み立て・送信・fan-out enqueue のすべてが業務コミット後に移るため、
 * 「REQUIRES_NEW で守った 1 箇所」ではなく<b>通知経路まるごと</b>が業務から切り離される。</p>
 *
 * <h2>イベントには ID だけを載せる</h2>
 * <p>キープ名・スコープ・遷移先はすべて業務データであるため積まず、配送側が {@code keepId} /
 * {@code convertedScheduleId} から読み直す。コミット後であれば内側から見えないという
 * 是正前の制約（{@code ScheduleKeepNotificationPublisher} が再検索を禁じていた理由）は
 * そもそも発生しない。</p>
 *
 * @param keepId              変換されたキープ ID（本文・受信者・可視性の読み直しキー）
 * @param convertedScheduleId 変換で生成された予定 {@code schedules.id}
 * @param actorUserId         変換操作者
 */
public record ScheduleKeepConvertedEvent(UUID keepId, Long convertedScheduleId, Long actorUserId) {
}
