package com.mannschaft.app.village.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 加入前相性表示（F17.2 §8.4）における「自分と重なる匿名村人数」のバケット。
 *
 * <p>差分攻撃（differential attack）対策として<strong>正確な人数は返さず</strong>、
 * 3人未満は非表示・3〜9人・10人以上の3段階に丸めた enum のみを返す（k-匿名性 k=3）。
 * バケット境界（3・10）をまたいだか以上の情報を攻撃者に与えない。</p>
 */
@Schema(description = "自分と重なる匿名村人数のバケット（正確な人数は返さない・§8.4）")
public enum SharedVillagerBucket {

    /** 重なり実数 n &lt; 3。非表示（誰とも縁が見えるほど重なっていない or 秘匿）。 */
    HIDDEN,

    /** 3 ≤ n ≤ 9。「数人」。 */
    FEW,

    /** n ≥ 10。「10人以上」。 */
    MANY
}
