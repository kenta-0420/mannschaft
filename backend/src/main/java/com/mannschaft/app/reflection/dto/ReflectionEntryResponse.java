package com.mannschaft.app.reflection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.ReflectionVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * <p><b>Phase 4（§13-C）</b>: マスク中の TERM_CARD section については、出題方向に応じた cue 側だけを
 * {@code maskedHint.cardQuiz} に載せる（答え側はフィールドごと載せない＝fail-closed・漏洩ゼロ）。</p>
 *
 * @param id               エントリID
 * @param themeId          テーマID
 * @param targetDate       対象日
 * @param isMasked         マスク中か
 * @param structuredContent 本文（マスク中 null・§2.3）
 * @param maskedHint       マスク中に見せてよいメタ（theme タイトル・想起予定日・カードクイズ cue）
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
     * マスク中でも表示してよいメタ情報（本文は含めない・§3.2 / §13-C）。
     *
     * @param themeTitle      テーマ名
     * @param targetDate      対象日
     * @param dueRecallDates  到来済み／予定の想起日（ヒント表示用・全件）
     * @param recallDirection 暗記カードの出題方向（§13-B・TERM_CARD が無い／today=null のとき null）
     * @param cardQuiz        TERM_CARD の cue 側だけを載せたクイズ（答え側フィールドは持たない・§13-C。空配列可）
     */
    @Builder
    public record MaskedHint(
            String themeTitle,
            LocalDate targetDate,
            List<LocalDate> dueRecallDates,
            RecallDirection recallDirection,
            List<MaskedCardQuiz> cardQuiz
    ) {
    }

    /**
     * マスク中の暗記カード section ごとのクイズ（cue 側のみ・§13-C）。
     *
     * <p>nested record 名前衝突回避（§13-C-2・{@code feedback_openapi_nested_schema_name_collision}）のため
     * {@code @Schema(name=...)} で一意名を付与する。</p>
     *
     * @param heading   section 見出し
     * @param direction 出題方向（この section の全カードで共通・§13-B-2）
     * @param prompts   cue 側だけのプロンプト一覧（答え側フィールドは含めない）
     */
    @Schema(name = "ReflectionMaskedCardQuiz")
    @Builder
    public record MaskedCardQuiz(
            String heading,
            RecallDirection direction,
            List<MaskedCardPrompt> prompts
    ) {
    }

    /**
     * マスク中の暗記カード 1 枚分の cue（表示側のみ・§13-C）。
     *
     * <p><b>答え側フィールド（term/meaning の他方）は定義しない＝fail-closed</b>。
     * {@code promptSide} は cue がどちらか（"MEANING" or "TERM"）を示す。</p>
     *
     * @param promptSide cue 側（"MEANING"＝意味を表示し語句入力 / "TERM"＝語句を表示し意味入力）
     * @param promptText cue として表示するテキスト（答え側は一切載せない）
     */
    @Schema(name = "ReflectionMaskedCardPrompt")
    @Builder
    public record MaskedCardPrompt(
            String promptSide,
            String promptText
    ) {
    }
}
