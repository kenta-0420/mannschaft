package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 加入前相性表示のレスポンス（F17.2 §8.3）。
 *
 * <p>非メンバーが「この村は自分と合いそうか」を掴むための「相性のヒント」。
 * プライバシー保護（G4・§8.4）のため、<strong>正確な重なり人数・重なった村人の identity は
 * 一切含まない</strong>（バケット化した {@link SharedVillagerBucket} のみ）。根拠は文言を焼き付けず
 * i18n キー配列（{@code reasonKeys}）で返し、FE が翻訳する（§8.5）。</p>
 */
@Schema(description = "加入前相性表示（相性のヒント）。正確な人数・identity は返さない（§8.3/§8.4）")
public record VillageAffinityResponse(

        @Schema(description = "自分の関心カテゴリと村カテゴリが一致するか", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean categoryMatch,

        @Schema(description = "自分と重なる匿名村人数のバケット（HIDDEN/FEW/MANY・正確人数は非返却）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        SharedVillagerBucket sharedVillagerBucket,

        @Schema(description = "相性の根拠一言の i18n キー配列（FE で翻訳・空配列可）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> reasonKeys,

        @Schema(description = "小規模村の「草分けアピール」を出すか（未参加×総現役メンバー10人以下・§8.8）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean pioneerAppeal,

        @Schema(description = "村の総現役メンバー数（公開情報。アピール判定の根拠・匿名重なりとは別軸）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long memberCount
) {
}
