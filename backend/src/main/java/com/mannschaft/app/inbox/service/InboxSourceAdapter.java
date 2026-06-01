package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.InboxItemDto;

import java.util.List;

/**
 * F04.11 統合通知インボックス：ソースアダプタ・インターフェース。
 *
 * <p>各通知ソース（5 種）を統一 DTO {@code InboxItemDto} に変換する。新ソース追加は
 * 「アダプタ 1 実装の追加」で済む（保守性）。読み取りのみ・書き込み越境なし（CLAUDE.md 原則5）。
 * 設計書: 03_business_logic.md §2。</p>
 *
 * <p>MVP で実装するのは NOTIFICATION / TODO_DUE の 2 アダプタ（残 3 ソースは出陣③）。</p>
 */
public interface InboxSourceAdapter {

    /**
     * このアダプタが担当するソース種別を返す。
     */
    InboxSourceType sourceType();

    /**
     * 当該ユーザーの通知をソースから読み取り、統一 DTO へ正規化して返す（<b>境界付きウィンドウ</b>）。
     *
     * <p>F04.11 Phase3 ③（境界付きウィンドウページング）: 各アダプタは「自分のソース内の順序の
     * 上位 {@code window} 件」だけを返す。集約サービスは全ソースの上位ウィンドウをマージ・完全全順序
     * ソートして {@code [page*size, (page+1)*size)} をスライスする。これにより各ソースを
     * 無制限 fetch せず（メモリ境界付き）、決定的（重複なし・load-more 連続）なページングを行う
     * （設計書 03_business_logic.md §4・§4.1）。</p>
     *
     * <p><b>実際の取りこぼし保証（不変条件と限界）</b>: 「自ソース内の上位 {@code window} 件にグローバル上位
     * 候補が漏れなく含まれる」ためには、<b>各ソースの fetch 順がグローバル全順序（priority 第一）と整合する</b>
     * 必要がある。整合するソースは取りこぼしなし、整合しないソースは稀な偏在で後ページ送りになりうる：
     * <ul>
     *   <li><b>MENTION・TODO_DUE・NOTIFICATION は取りこぼしなし</b>。MENTION は priority 一律 HIGH ゆえ
     *       新着順＝グローバル順。TODO_DUE は due_date 昇順が priority 降順（期限切れ=URGENT→当日=HIGH→近接）
     *       と整合。NOTIFICATION は priority 第一クエリ
     *       （{@code findInboxByUserIdOrderByPriorityThenCreatedAtDesc}）で fetch 順をグローバル順に一致させる。</li>
     *   <li><b>ANNOUNCEMENT・CONFIRMABLE は取得順が priority と独立</b>（pinned/created_at・親 created_at）。
     *       極端な偏在（古い URGENT お知らせが多数の新着に埋もれる／確認締切 24h 昇格による稀な順位逆転）では、
     *       高 priority・低時刻の項目が後ページへ送られうる。pinned 件数・未確認の保留件数は通常小さく
     *       「直近の仕分け場」用途では実害が限定的なため安全側に据え置く（共有 {@code findByScope} を
     *       壊さない／24h 昇格の SQL 順序化は将来課題・設計書 §4.1）。</li>
     * </ul>
     * いずれも集約側は {@code window >= (page+1)*size} を満たすウィンドウで取得する。「ゼロ」と断定はしない。</p>
     *
     * @param userId 対象ユーザーID
     * @param window 取得上限件数（このソースから返す最大件数。{@code <= 0} は 0 件扱い）
     * @return 正規化済みインボックス項目（最大 {@code window} 件・triage 状態/ラベルは未マージ＝集約サービスで被せる）
     */
    List<InboxItemDto> fetch(Long userId, int window);

    /**
     * 指定通知（{@code sourceId}）が当該ユーザーに可視か判定する（IDOR 防止・triage 書き込み前検証）。
     *
     * <p>一覧取得と同じ可視性ロジックを再利用し、本人宛てでない通知への triage/ラベル付与を弾く
     * （設計書 04_security_operations.md §1.2）。</p>
     *
     * @param userId   対象ユーザーID
     * @param sourceId 各ソース PK
     * @return 本人に可視なら true
     */
    boolean isVisibleTo(Long userId, Long sourceId);
}
