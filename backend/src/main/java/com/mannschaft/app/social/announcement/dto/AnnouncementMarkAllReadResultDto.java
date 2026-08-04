package com.mannschaft.app.social.announcement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 一括既読（{@code POST .../announcements/read-all}）の結果 DTO（F02.6 / #2530 ①）。
 *
 * <p><b>なぜ DTO にしたか</b>: 従来この応答は {@code Map.of("markedCount", 0)} という
 * 素の {@code Map<String, Object>} で、しかも値がハードコードの {@code 0} だった。
 * {@code Map} は OpenAPI スキーマに構造として現れないため、設計書（当時 {@code marked_count}）と
 * 実装（{@code markedCount}）のキー名の食い違いを機械的に検出できなかった。
 * DTO 化により {@code docs/openapi.json} と FE の生成型にキー名が現れ、以後のドリフトは
 * 型生成で検出できる。</p>
 *
 * <p><b>キー名の決着</b>: プロジェクト規約の <b>camelCase</b> を正とし、設計書 F02.6 §4 の
 * {@code marked_count}（snake_case）表記を実装側に合わせて改めた
 * （他エンドポイントの応答・{@code @RequestBody} と統一するため）。</p>
 *
 * <p>クラス名に {@code Announcement} を冠しているのは、掲示板ドメインの
 * {@code GlobalBulletinThreadController.MarkAllReadResult} と OpenAPI のスキーマ名
 * （springdoc は単純クラス名を使う）で衝突させないためである。</p>
 */
@Getter
@Builder
@Schema(description = "一括既読の結果")
public class AnnouncementMarkAllReadResultDto {

    /**
     * このリクエストで新たに既読化した件数（既読済みだったものは含まない）。
     */
    @Schema(description = "このリクエストで新たに既読化した件数", example = "8")
    private final int markedCount;

    /**
     * 1 リクエストの防御上限（{@code 500 件 × 20 チャンク = 10,000 件}）に到達して打ち切り、
     * <b>未読が残っている</b>かどうか。
     *
     * <p>{@code true} のとき FE は「未読 0」と表示してはならない。もう一度一括既読を実行すれば
     * 続きが処理される。</p>
     */
    @Schema(description = "打ち切りにより未読が残っているか（true ならもう一度実行すると続きが処理される）",
            example = "false")
    private final boolean hasMoreUnread;
}
