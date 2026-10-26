package com.mannschaft.app.corkboard.event;

import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;

/**
 * F09.8 Phase A-3 / 件B: コルクボード WebSocket 配信イベント。
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} 経由で
 * STOMP トピック {@code /topic/corkboard/{boardId}} に配信される。</p>
 *
 * <p>個人ボード（PERSONAL）には配信しない。共有ボード（TEAM/ORGANIZATION）でのみ
 * パブリッシュする想定（呼び出し側で判定）。</p>
 *
 * <p>件B 拡張 (2026-05-03):</p>
 * <ul>
 *   <li>{@link #card} / {@link #section} ペイロードを追加し、フロント側で
 *       eventType 別の局所更新（push / map / filter）を可能にした。</li>
 *   <li>従来は cardId / sectionId のみ配信していたため、フロントは受信のたびに
 *       {@code load()} でボード詳細をフルリロードする必要があった。新ペイロードを
 *       使えば API 呼び出しを 1 回節約でき、UX も向上する。</li>
 *   <li>互換性: 既存ファクトリ（{@link #card(Long, Type, Long)} ほか）は維持し、
 *       payload なしの古い呼び出しでも動作する。フロントは {@code event.card == null}
 *       のとき従来どおりフルリロードへフォールバックする。</li>
 * </ul>
 *
 * <p>対応イベント種別（10 種）:</p>
 * <ul>
 *   <li>CARD_CREATED / CARD_MOVED / CARD_UPDATED / CARD_DELETED / CARD_ARCHIVED</li>
 *   <li>SECTION_CREATED / SECTION_UPDATED / SECTION_DELETED</li>
 *   <li>CARD_SECTION_CHANGED</li>
 *   <li>BOARD_DELETED</li>
 * </ul>
 *
 * @param boardId   対象ボードID
 * @param eventType イベント種別
 * @param cardId    対象カードID（CARD_* / CARD_SECTION_CHANGED の場合）
 * @param sectionId 対象セクションID（SECTION_* / CARD_SECTION_CHANGED の場合）
 * @param card      対象カードの完成 DTO（CARD_CREATED / CARD_UPDATED / CARD_MOVED /
 *                  CARD_ARCHIVED / CARD_SECTION_CHANGED 時のみ非 null。それ以外は null）
 * @param section   対象セクションの完成 DTO（SECTION_CREATED / SECTION_UPDATED 時のみ
 *                  非 null。それ以外は null）
 */
public record CorkboardEvent(
        Long boardId,
        Type eventType,
        Long cardId,
        Long sectionId,
        CorkboardCardResponse card,
        CorkboardGroupResponse section
) {

    /** イベント種別。 */
    public enum Type {
        CARD_CREATED,
        CARD_MOVED,
        CARD_UPDATED,
        CARD_DELETED,
        CARD_ARCHIVED,
        SECTION_CREATED,
        SECTION_UPDATED,
        SECTION_DELETED,
        CARD_SECTION_CHANGED,
        BOARD_DELETED
    }

    // ============================================================
    // 旧ファクトリ（payload なし）— 互換維持。フロントは null フォールバック。
    // ============================================================

    public static CorkboardEvent card(Long boardId, Type type, Long cardId) {
        return new CorkboardEvent(boardId, type, cardId, null, null, null);
    }

    public static CorkboardEvent section(Long boardId, Type type, Long sectionId) {
        return new CorkboardEvent(boardId, type, null, sectionId, null, null);
    }

    public static CorkboardEvent cardSection(Long boardId, Long cardId, Long sectionId) {
        return new CorkboardEvent(boardId, Type.CARD_SECTION_CHANGED, cardId, sectionId, null, null);
    }

    public static CorkboardEvent boardDeleted(Long boardId) {
        return new CorkboardEvent(boardId, Type.BOARD_DELETED, null, null, null, null);
    }

    // ============================================================
    // 件B 新ファクトリ（payload あり）— フロント局所更新対応。
    // ============================================================

    /** カード作成: 新規カードの完成 DTO を含めて配信する。 */
    public static CorkboardEvent cardCreated(Long boardId, CorkboardCardResponse card) {
        return new CorkboardEvent(boardId, Type.CARD_CREATED, card.getId(), null, card, null);
    }

    /** カード更新: 更新後カード DTO を含めて配信する。 */
    public static CorkboardEvent cardUpdated(Long boardId, CorkboardCardResponse card) {
        return new CorkboardEvent(boardId, Type.CARD_UPDATED, card.getId(), null, card, null);
    }

    /** カード移動（位置変更）: 更新後カード DTO を含めて配信する。 */
    public static CorkboardEvent cardMoved(Long boardId, CorkboardCardResponse card) {
        return new CorkboardEvent(boardId, Type.CARD_MOVED, card.getId(), null, card, null);
    }

    /** カードアーカイブ切替: 更新後カード DTO を含めて配信する。 */
    public static CorkboardEvent cardArchived(Long boardId, CorkboardCardResponse card) {
        return new CorkboardEvent(boardId, Type.CARD_ARCHIVED, card.getId(), null, card, null);
    }

    /**
     * カード削除: フロントは cardId のみで filter できるため card は null とする。
     */
    public static CorkboardEvent cardDeleted(Long boardId, Long cardId) {
        return new CorkboardEvent(boardId, Type.CARD_DELETED, cardId, null, null, null);
    }

    /** セクション作成: 新規セクションの完成 DTO を含めて配信する。 */
    public static CorkboardEvent sectionCreated(Long boardId, CorkboardGroupResponse section) {
        return new CorkboardEvent(boardId, Type.SECTION_CREATED, null, section.getId(), null, section);
    }

    /** セクション更新: 更新後セクション DTO を含めて配信する。 */
    public static CorkboardEvent sectionUpdated(Long boardId, CorkboardGroupResponse section) {
        return new CorkboardEvent(boardId, Type.SECTION_UPDATED, null, section.getId(), null, section);
    }

    /**
     * セクション削除: フロントは sectionId のみで filter できるため section は null とする。
     */
    public static CorkboardEvent sectionDeleted(Long boardId, Long sectionId) {
        return new CorkboardEvent(boardId, Type.SECTION_DELETED, null, sectionId, null, null);
    }

    /**
     * カードのセクション紐付け変更: 更新後カード DTO（{@code sectionId} 含む）を含めて配信する。
     *
     * @param card 更新後のカード DTO（{@code card.sectionId} は新しい所属セクション ID）
     * @param sectionId 新しい所属セクション ID（解除の場合は {@code null}）
     */
    public static CorkboardEvent cardSectionChanged(Long boardId, CorkboardCardResponse card, Long sectionId) {
        return new CorkboardEvent(boardId, Type.CARD_SECTION_CHANGED, card.getId(), sectionId, card, null);
    }
}
