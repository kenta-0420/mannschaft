package com.mannschaft.app.reflection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.ReflectionVisibility;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * エントリレスポンス（F06.5・§7 #6〜#10・§3.2 マスク分離）。
 *
 * <p><b>マスク中は本文をソースから詰めない</b>: {@code isMasked=true} のとき {@code structuredContent=null}、
 * {@code maskedHint} のみ（theme タイトル・target_date・想起予定日）を返す（§3.2・AC-8）。
 * これが Mapper による唯一の生成口で、後段で握り潰すのではなくソースで null にする。</p>
 *
 * @param id               エントリID
 * @param themeId          テーマID
 * @param targetDate       対象日
 * @param isMasked         マスク中か
 * @param structuredContent 本文（マスク中 null・§2.3）
 * @param maskedHint       マスク中に見せてよいメタ（theme タイトル・想起予定日）
 * @param visibility       可視性（MVP は PRIVATE）
 * @param version          楽観ロックバージョン（PUT の expectedVersion 突合用）
 * @param updatedAt        更新日時
 * @param exportedBlogPostId 輸出済みブログ投稿ID（null=未輸出）。マスク中でも本文ではないメタとして開示してよい（再輸出 409 回避導線・follow-up A④）
 */
@Builder
public record ReflectionEntryResponse(
        String id,
        String themeId,
        LocalDate targetDate,
        boolean isMasked,
        JsonNode structuredContent,
        MaskedHint maskedHint,
        ReflectionVisibility visibility,
        Long version,
        LocalDateTime updatedAt,
        Long exportedBlogPostId
) {

    /**
     * マスク中でも表示してよいメタ情報（本文は含めない・§3.2）。
     *
     * @param themeTitle    テーマ名
     * @param targetDate    対象日
     * @param dueRecallDates 到来済み／予定の想起日（ヒント表示用）
     */
    @Builder
    public record MaskedHint(
            String themeTitle,
            LocalDate targetDate,
            List<LocalDate> dueRecallDates
    ) {
    }
}
