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
     * <p>F04.11 Phase3 ③（境界付きウィンドウページング）: 各アダプタは「自分のソース内で正しい順序
     * （新着優先・MENTION 等は一律順）の上位 {@code window} 件」だけを返す。集約サービスは全ソースの
     * 上位ウィンドウをマージ・全順序ソートして {@code [page*size, (page+1)*size)} をスライスする。
     * これにより、各ソースを無制限 fetch せず（メモリ境界付き）、かつ指定ページの直近上位を取りこぼさない
     * （設計書 03_business_logic.md §4）。</p>
     *
     * <p><b>取りこぼしゼロの根拠</b>: 各ソースが自ソース内の上位 {@code window} 件を返せば、
     * グローバル上位 {@code window} 件は必ずその和集合に含まれる（各ソースは自分の中で正しい順序で
     * 上位を返すため、グローバル上位に入りうる項目を window 内で漏らさない）。集約側は
     * {@code window >= (page+1)*size} を満たすウィンドウで取得するため、当該ページ内の項目は欠落しない。</p>
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
