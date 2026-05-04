package com.mannschaft.app.corkboard.event;

/**
 * F09.8 Phase A-3: コルクボード WebSocket 配信イベント。
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} 経由で
 * STOMP トピック {@code /topic/corkboard/{boardId}} に配信される。</p>
 *
 * <p>個人ボード（PERSONAL）には配信しない。共有ボード（TEAM/ORGANIZATION）でのみ
 * パブリッシュする想定（呼び出し側で判定）。</p>
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
 */
public record CorkboardEvent(
        Long boardId,
        Type eventType,
        Long cardId,
        Long sectionId
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

    public static CorkboardEvent card(Long boardId, Type type, Long cardId) {
        return new CorkboardEvent(boardId, type, cardId, null);
    }

    public static CorkboardEvent section(Long boardId, Type type, Long sectionId) {
        return new CorkboardEvent(boardId, type, null, sectionId);
    }

    public static CorkboardEvent cardSection(Long boardId, Long cardId, Long sectionId) {
        return new CorkboardEvent(boardId, Type.CARD_SECTION_CHANGED, cardId, sectionId);
    }

    public static CorkboardEvent boardDeleted(Long boardId) {
        return new CorkboardEvent(boardId, Type.BOARD_DELETED, null, null);
    }
}
