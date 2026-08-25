package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知配送の 1 件送信用 REQUIRES_NEW 実行 Bean（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code schedule/service/ScheduleCommentNotificationRunner}（PR #2655 系・59行）と同形の金型を
 * ドメイン非依存に一般化したもの。配送リスナー（{@code AFTER_COMMIT + @Async}）が受信者ごとに
 * ループで本 Bean を呼ぶ。バッチ失敗時のリトライ安全性・他受信者への巻き添え防止のため、
 * <b>1 件の通知送信 = 1 独立トランザクション</b>とする必要があり、独立した Bean に切り出し
 * {@link Propagation#REQUIRES_NEW} を付与する（同一 Bean 内の自己呼び出しではプロキシを経由せず
 * 伝播設定が効かないため）。</p>
 *
 * <h2>業務サービスから直接呼んではならない</h2>
 * <p>本 Bean は業務トランザクションの外（{@code AFTER_COMMIT} リスナー）からのみ呼び出すこと。
 * 業務サービスのメソッド内から直接呼ぶと、本 Bean の {@code REQUIRES_NEW} は独立トランザクションに
 * なるものの、業務コミット前に通知が先に確定してしまい、業務側がロールバックした場合に
 * 「本処理は消えたのに通知だけ残る」逆向きの不整合を生む（Issue #2834 の根本原因と対称の欠陥）。</p>
 *
 * <h2>{@link NotificationService#createNotification} の伝播設定は変更しない</h2>
 * <p>{@code createNotification} 自体は既定の {@code REQUIRED} のまま据え置く。他の呼び出し元
 * （本 Bean を経由しない既存45箇所）に影響させないため。</p>
 *
 * <h2>戻り値: visibility deny と成功の区別</h2>
 * <p>{@code createNotification} は visibility deny 時に例外を投げず {@code null} を返す
 * （§11.1・NotificationService 内で既に WARN ログを残す）。本 Bean はその戻り値をそのまま返し、
 * 呼び出し元の配送リスナーが「deny（WARN 相当・null 復帰）」と「DB 例外（ERROR 相当・例外送出で
 * このトランザクションはロールバック）」を区別できるようにする。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryRunner {

    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;

    /**
     * 1 件の通知を独立トランザクションで送信する。
     *
     * @param request 通知配送要求
     * @return 作成された通知エンティティ。visibility deny の場合は {@code null}
     *         （呼び出し元は null を「非例外の deny」として扱うこと。例外は DB 障害等を意味し、
     *         このトランザクションはロールバックされて呼び出し元へ伝播する）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationEntity sendOne(NotificationDeliveryRequest request) {
        NotificationEntity created = notificationService.createNotification(
                request.recipientUserId(),
                request.notificationType(),
                request.priority(),
                request.title(),
                request.body(),
                request.sourceType(),
                request.sourceId(),
                request.scopeType(),
                request.scopeId(),
                request.actionUrl(),
                request.actorId());
        if (created == null) {
            // visibility deny。NotificationService 側で既に WARN 済みのため、ここでは二重ログしない。
            return null;
        }
        notificationDispatchService.dispatch(created);
        return created;
    }
}
