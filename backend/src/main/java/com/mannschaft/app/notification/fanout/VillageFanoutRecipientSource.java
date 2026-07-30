package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * VILLAGE スコープの受信者ソース（P2）。村の現役 USER メンバーを
 * {@link VillageMembershipRepository#findActiveUserSubjectIdsByVillageIdKeyset} でキーセット供給する。
 *
 * <h2>⚠ 出陣（green）で解決すべき設計上の宙づり — scope_id(BIGINT) ↔ 村UUID</h2>
 * <p>ジョブ表の {@code scope_id} は {@code BIGINT} だが、村の主キーは {@code UUID}（{@code BINARY(16)}）で
 * BIGINT に収まらない。したがって {@code nextPage(long scopeId, ...)} の {@code scopeId} だけでは村を特定できない。
 * 本試練（red）では未解決のまま {@link UnsupportedOperationException} を投げて<b>握り潰さずに露見させる</b>。</p>
 *
 * <p>出陣での解決候補（{@code 申し送り} 参照。いずれかをマスター承認のうえ採用）:</p>
 * <ol>
 *   <li><b>推奨</b>: ジョブ表に {@code scope_uuid BINARY(16) NULL} を追加し、UUID スコープは {@code scope_uuid} で、
 *       BIGINT スコープ（TEAM 等）は {@code scope_id} で解決する。{@link FanoutRecipientSource} も
 *       UUID/long 両対応のシグネチャに拡張する。</li>
 *   <li>代替: 村に surrogate BIGINT を発番する（既存 UUID 方針に反するため非推奨）。</li>
 * </ol>
 *
 * <p>この宙づりは VILLAGE 実配線の話であり、ワーカー／ジョブ機構そのものの red/green は
 * テスト用の合成 {@code FanoutRecipientSource}（{@code TEST_IT_SCOPE}）で検証する（AC-2/AC-3/AC-15）。</p>
 */
@Component
@RequiredArgsConstructor
public class VillageFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー。{@link com.mannschaft.app.notification.NotificationScopeType} とは独立の戦略キー。 */
    public static final String SCOPE_TYPE = "VILLAGE";

    @SuppressWarnings("unused") // green で UUID 解決に配線する（現状は宙づりのため未使用）
    private final VillageMembershipRepository membershipRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<Long> nextPage(long scopeId, long cursorSubjectId, int limit) {
        // P2 出陣で scope_id(BIGINT) → 村UUID の解決手段を確定してから配線する（クラス Javadoc 参照）。
        // 対処療法で誤った long→UUID 変換を仕込まず、未解決を明示する。
        throw new UnsupportedOperationException(
                "VILLAGE 受信者解決は scope_id(BIGINT)↔村UUID の設計確定後に P2 出陣で実装する");
    }
}
