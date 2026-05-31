package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * F04.11 統合通知インボックス：自動緊急度の正規化（純粋関数）。
 *
 * <p>各ソースの優先度を単一 {@link InboxPriority} に写像する。毎リクエスト導出（永続化しない）。
 * 正規化表は設計書 01_data_model.md §3.2 を参照。</p>
 *
 * <p><b>骨組み（一陣 → 二陣で時刻/TZ 注入用オーバーロードを追加）</b>: ロジック本体は三陣で実装する。
 * 現段階ではコンパイルが通る空骨格（{@link UnsupportedOperationException} を投げる）。</p>
 *
 * <p><b>二陣メモ（test-first での追加・三陣へ申し送り）</b>: TODO_DUE の暦日境界判定と
 * CONFIRMABLE の「締切 24h 以内昇格」は <b>ユーザー TZ での現在時刻</b>に依存する純粋関数であり、
 * 決定的にテストするには時刻/TZ を引数注入できる必要がある（設計書 03_business_logic.md §3）。
 * そのため {@link NormalizationContext} を受け取るオーバーロード 2 本を追加した。
 * 三陣は基本の {@link #normalize(InboxSourceType, String)} を「時刻非依存ソース
 * （NOTIFICATION/ANNOUNCEMENT/MENTION）」用に実装し、TODO_DUE/CONFIRMABLE は
 * context を取るオーバーロードで実装すること（シグネチャは集約サービスの呼び出し都合で調整可）。</p>
 */
@Component
public class InboxPriorityNormalizer {

    /**
     * 時刻非依存ソースの優先度を導出する（NOTIFICATION / ANNOUNCEMENT / MENTION）。
     *
     * @param sourceType    ソース種別
     * @param rawPriority   ソース固有の優先度（null 可）
     * @return 正規化後の緊急度
     */
    public InboxPriority normalize(InboxSourceType sourceType, String rawPriority) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * TODO_DUE の優先度を due_date 基準で導出する（ユーザー TZ の暦日で判定）。
     *
     * @param dueDate due_date（期限）
     * @param ctx     現在時刻・TZ を含む正規化コンテキスト
     * @return 期限切れ=URGENT / 当日=HIGH / 3 日内=NORMAL / それ以遠=LOW（対象外相当）
     */
    public InboxPriority normalizeTodoDue(LocalDateTime dueDate, NormalizationContext ctx) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * CONFIRMABLE の優先度を導出する（親 priority 写像＋未確認かつ締切 24h 以内は URGENT 昇格）。
     *
     * @param parentRawPriority 親 confirmable_notifications の priority（NORMAL/HIGH/URGENT）
     * @param deadline          確認締切（null 可＝昇格判定なし）
     * @param confirmed         本人が確認済みか
     * @param ctx               現在時刻・TZ を含む正規化コンテキスト
     * @return 正規化後の緊急度
     */
    public InboxPriority normalizeConfirmable(
            String parentRawPriority, LocalDateTime deadline, boolean confirmed, NormalizationContext ctx) {
        throw new UnsupportedOperationException("not implemented");
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
