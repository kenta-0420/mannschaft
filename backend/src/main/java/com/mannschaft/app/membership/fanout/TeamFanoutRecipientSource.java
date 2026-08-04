package com.mannschaft.app.membership.fanout;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TEAM スコープの受信者ソース（fan-out 抜本改修 Wave-1）。チームの現役メンバー（{@code left_at IS NULL}）を
 * {@link MembershipRepository#findActiveUserIdsByScopeKeyset} でキーセット供給する。
 *
 * <h2>配置ドメイン（越境 Repository 依存の解消・D-5 番人）</h2>
 * <p>受信者解決は memberships Repository を引くため、本実装は<b>membership ドメイン</b>
 * （{@code com.mannschaft.app.membership.fanout}）に置く。notification ドメインに置くと
 * {@code MembershipRepository}（membership ドメイン）への越境 Repository 依存となり
 * {@code CrossDomainRepositoryDependencyArchTest}（D-5）が fail する。戦略シームの共有契約
 * {@link FanoutRecipientSource} は notification/fanout に残し、membership 側が実装する（依存性逆転）。
 * {@code FanoutRecipientSourceRegistry} は {@code List<FanoutRecipientSource>} 注入のため、
 * 実装がどのドメインにあっても Spring が自動登録し結線は維持される（村実装 {@code VillageFanoutRecipientSource} と同型）。</p>
 *
 * <h2>scope_ref による TEAM ID の復元</h2>
 * <p>ジョブ表の多型スコープ参照 {@code scope_ref}（VARCHAR）には対象チームの ID を文字列で格納する。
 * 本実装はこれを long へ復元し、被覆索引 {@code idx_membership_fanout_keyset}（V174 migration）を用いた
 * キーセットページングで受信者 user_id を 1 チャンクずつ返す。現役判定（{@code left_at IS NULL}）は
 * リポジトリのクエリに閉じ込め、退会者を漏れなく除外する。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー。{@link ScopeType#TEAM} に対応する戦略キー。 */
    public static final String SCOPE_TYPE = "TEAM";

    private final MembershipRepository membershipRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit) {
        // scope_ref にはチーム ID を文字列で格納しているため long へ復元する（多型スコープ参照）。
        long scopeId = Long.parseLong(scopeRef);
        // 現役判定（left_at IS NULL）と scope_id 等値絞り込みはリポジトリのクエリに閉じ込め、
        // 被覆索引 idx_membership_fanout_keyset で 1 チャンクぶんの user_id を昇順に返す。
        return membershipRepository.findActiveUserIdsByScopeKeyset(
                ScopeType.TEAM, scopeId, cursorSubjectId, PageRequest.of(0, limit));
    }
}
