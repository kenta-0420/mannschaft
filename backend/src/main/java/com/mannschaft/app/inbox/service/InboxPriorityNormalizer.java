package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * F04.11 統合通知インボックス：自動緊急度の正規化（純粋関数）。
 *
 * <p>各ソースの優先度を単一 {@link InboxPriority} に写像する。毎リクエスト導出（永続化しない）。
 * 正規化表は設計書 01_data_model.md §3.2 を参照。</p>
 *
 * <p>TODO_DUE の暦日境界判定（期限切れ/当日/3 日内）と CONFIRMABLE の「締切 24h 以内昇格」は
 * <b>ユーザー TZ での現在時刻</b>に依存するため、{@link NormalizationContext} で時刻/TZ を注入し
 * 決定的に評価する（設計書 03_business_logic.md §3）。</p>
 */
@Component
public class InboxPriorityNormalizer {

    /**
     * 時刻非依存ソースの優先度を導出する（NOTIFICATION / ANNOUNCEMENT / MENTION）。
     *
     * <ul>
     *   <li>NOTIFICATION: 同値写像（URGENT/HIGH/NORMAL/LOW）</li>
     *   <li>ANNOUNCEMENT: URGENT→URGENT / IMPORTANT→HIGH / NORMAL→NORMAL</li>
     *   <li>MENTION: 優先度概念なし＝一律 HIGH（rawPriority は無視）</li>
     * </ul>
     *
     * @param sourceType    ソース種別
     * @param rawPriority   ソース固有の優先度（null 可）
     * @return 正規化後の緊急度
     */
    public InboxPriority normalize(InboxSourceType sourceType, String rawPriority) {
        return switch (sourceType) {
            case NOTIFICATION -> mapNotification(rawPriority);
            case ANNOUNCEMENT -> mapAnnouncement(rawPriority);
            case MENTION -> InboxPriority.HIGH;
            case CONFIRMABLE, TODO_DUE -> throw new IllegalArgumentException(
                    sourceType + " は時刻依存のため normalizeConfirmable/normalizeTodoDue を使用すること");
        };
    }

    private InboxPriority mapNotification(String rawPriority) {
        if (rawPriority == null) {
            return InboxPriority.NORMAL;
        }
        return switch (rawPriority) {
            case "URGENT" -> InboxPriority.URGENT;
            case "HIGH" -> InboxPriority.HIGH;
            case "LOW" -> InboxPriority.LOW;
            default -> InboxPriority.NORMAL;
        };
    }

    private InboxPriority mapAnnouncement(String rawPriority) {
        if (rawPriority == null) {
            return InboxPriority.NORMAL;
        }
        return switch (rawPriority) {
            case "URGENT" -> InboxPriority.URGENT;
            case "IMPORTANT" -> InboxPriority.HIGH;
            default -> InboxPriority.NORMAL;
        };
    }

    /**
     * TODO_DUE の優先度を due_date 基準で導出する（ユーザー TZ の暦日で判定）。
     *
     * <p>期限切れ（基準日より前の暦日）=URGENT / 当日=HIGH / 3 日内（翌日〜3 日後）=NORMAL /
     * それ以遠（4 日後以降）=LOW。暦日は {@code ctx.zoneId()} で切り出す。</p>
     *
     * @param dueDate due_date（期限）
     * @param ctx     現在時刻・TZ を含む正規化コンテキスト
     * @return 正規化後の緊急度
     */
    public InboxPriority normalizeTodoDue(LocalDateTime dueDate, NormalizationContext ctx) {
        ZoneId zone = ctx.zoneId();
        LocalDate today = ctx.now().atZone(zone).toLocalDate();
        LocalDate due = dueDate.atZone(zone).toLocalDate();

        long daysUntil = ChronoUnit.DAYS.between(today, due);
        if (daysUntil < 0) {
            return InboxPriority.URGENT;   // 期限切れ
        }
        if (daysUntil == 0) {
            return InboxPriority.HIGH;     // 当日
        }
        if (daysUntil <= 3) {
            return InboxPriority.NORMAL;   // 3 日内
        }
        return InboxPriority.LOW;          // それ以遠（対象外相当）
    }

    /**
     * CONFIRMABLE の優先度を導出する（親 priority 写像＋未確認かつ締切 24h 以内は URGENT 昇格）。
     *
     * <p>確認済み（{@code confirmed=true}）の場合は昇格しない。締切が null の場合も昇格判定なし。
     * 「24h 以内」は {@code deadline - now <= 24h} かつ {@code deadline >= now}（過去締切は別途
     * URGENT 相当だが、本写像は未来締切の昇格に限定する）。</p>
     *
     * @param parentRawPriority 親 confirmable_notifications の priority（NORMAL/HIGH/URGENT）
     * @param deadline          確認締切（null 可＝昇格判定なし）
     * @param confirmed         本人が確認済みか
     * @param ctx               現在時刻・TZ を含む正規化コンテキスト
     * @return 正規化後の緊急度
     */
    public InboxPriority normalizeConfirmable(
            String parentRawPriority, LocalDateTime deadline, boolean confirmed, NormalizationContext ctx) {
        InboxPriority base = mapNotification(parentRawPriority);

        if (!confirmed && deadline != null) {
            LocalDateTime now = ctx.now();
            // 締切が現時点〜24h 以内（未来側）なら URGENT に昇格
            if (!deadline.isBefore(now) && !deadline.isAfter(now.plusHours(24))) {
                return InboxPriority.URGENT;
            }
        }
        return base;
    }

    /**
     * 時刻依存正規化のコンテキスト（決定的テスト用に「現在時刻」と TZ を注入する）。
     *
     * @param now    判定基準の現在時刻
     * @param zoneId ユーザーのアカウントタイムゾーン
     */
    public record NormalizationContext(LocalDateTime now, ZoneId zoneId) {
    }
}
