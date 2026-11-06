package com.mannschaft.app.team.dto;

import java.util.UUID;

/**
 * 組織による加盟申請の承認リクエスト（F01.2.1 §10.5）。
 *
 * <p>「キーを送らない」と「null を送る」を区別しないため、グループを上書きするかどうかを
 * {@code overrideGroup} で明示させる（§6.2 step 4）。入力の検証は認可より<b>後</b>に Service が行う
 * （認可の順序: 認証 → スコープの存在 → 権限 → 入力検証 → 状態。§10）ため、Bean Validation は付けない。</p>
 *
 * @param overrideGroup 必須。{@code false} = 申請時の希望グループのまま承認する（{@code groupId} は無視する）。
 *                      {@code true} = {@code groupId} のグループで承認する
 * @param groupId       {@code overrideGroup=true} のときだけ使う。null なら未分類で承認する
 */
public record ApproveTeamApplicationRequest(Boolean overrideGroup, UUID groupId) {
}
