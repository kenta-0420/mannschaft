package com.mannschaft.app.role.fanout;

import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ORGANIZATION スコープの受信者ソース（fan-out 抜本改修 Wave-2・ORG 耐久 fan-out）。
 *
 * <p>対象組織を根とした<b>全子孫組織ツリー</b>（直属 ∪ 配下 ACTIVE チーム）の現役メンバーを
 * {@link UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset} で
 * <b>キーセットページング</b>供給する。母集団条件（{@code users.deleted_at IS NULL AND status='ACTIVE'}・
 * 応援者トグル・{@code team_org_memberships.status='ACTIVE'} のみ展開）はすべてリポジトリのクエリに閉じ込め、
 * 本実装は scope_ref の復元とトグル運搬のみを担う。</p>
 *
 * <h2>配置ドメイン（越境 Repository 依存の解消・D-5 番人）</h2>
 * <p>受信者解決は {@code UserRoleRepository}（role ドメイン）を引くため、本実装は<b>role ドメイン</b>
 * （{@code com.mannschaft.app.role.fanout}）に置く。notification / organization ドメインに置くと
 * role ドメインの Repository への越境依存となり {@code CrossDomainRepositoryDependencyArchTest}（D-5）が
 * fail する。共有契約 {@link FanoutRecipientSource} は notification/fanout に残し、role 側が実装する
 * （依存性逆転・TEAM 版 {@code TeamFanoutRecipientSource} と同型）。
 * {@code FanoutRecipientSourceRegistry} は {@code List<FanoutRecipientSource>} 注入のため、
 * 実装がどのドメインにあっても Spring が自動登録し結線は維持される。</p>
 *
 * <h2>includeSupporters（応援者トグル）の運搬</h2>
 * <p>ワーカーが渡す 4 引数版 {@link #nextPage(String, long, int, boolean)} を override し、ジョブの
 * {@code include_supporters} をそのまま keyset クエリの {@code includeSupporters} パラメータへ渡す。
 * false のとき純 SUPPORTER（MEMBER 兼務でない応援者のみ）を除外する（母集団は repo クエリに閉じる）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrgFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー。ORGANIZATION スコープの戦略キー（ジョブ表 {@code scope_type} と一致）。 */
    public static final String SCOPE_TYPE = "ORGANIZATION";

    /**
     * ORG 再帰展開のサイクル防止上限。{@code OrganizationMembershipService.MAX_ORG_DESCENDANT_DEPTH}（=32）と同値。
     * 自己参照・深ネストの組織ツリーでも {@code depth < maxDepth} で確実に停止する。
     */
    static final int MAX_ORG_DESCENDANT_DEPTH = 32;

    private final UserRoleRepository userRoleRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    /**
     * 3 引数版は応援者トグル既定 true（全員配信）で 4 引数版へ委譲する。
     * 通常ワーカーは 4 引数版を呼ぶため、本メソッドは後方互換のフォールバック。
     */
    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit) {
        return nextPage(scopeRef, cursorSubjectId, limit, true);
    }

    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit, boolean includeSupporters) {
        // scope_ref には組織 ID を文字列で格納しているため long へ復元する（多型スコープ参照）。
        long organizationId = Long.parseLong(scopeRef);
        // 直属 ∪ 配下 ACTIVE チームの現役メンバーを user_id 昇順・キーセットで 1 チャンク供給する。
        // 母集団条件（ACTIVE・未削除・応援者トグル・配下チーム展開）は repo クエリに閉じ込める。
        // chunk は UNION 各枝の打ち切り件数。外側のページサイズと同じ値を渡す
        // （各枝から chunk 件ずつ取れば和集合の先頭 chunk 件は必ずその中に含まれる）。
        return userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                organizationId, includeSupporters, MAX_ORG_DESCENDANT_DEPTH, cursorSubjectId,
                limit, PageRequest.of(0, limit));
    }

    /**
     * シャード対応 6 引数版（CMP-001⑤ ワーカー並列化）。
     *
     * <p>{@code shardCount <= 1} は分割せず 4 引数版へ委譲し従来経路と完全一致させる。{@code shardCount > 1} のみ
     * keyset クエリに {@code MOD(user_id, shardCount) = shardIndex} 述語を足したシャード版クエリを引き、
     * 自シャード担当（{@code user_id % shardCount == shardIndex}）の受信者だけを昇順・キーセットで供給する。
     * 母集団条件・SUPPORTER 除外・keyset は非シャード版と共有（差分は MOD 述語 1 行のみ）。</p>
     */
    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit, boolean includeSupporters,
                               int shardIndex, int shardCount) {
        if (shardCount <= 1) {
            // 単一シャード（従来経路）は非シャード版と完全一致（MOD 述語を挟まない）。
            return nextPage(scopeRef, cursorSubjectId, limit, includeSupporters);
        }
        long organizationId = Long.parseLong(scopeRef);
        return userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeysetSharded(
                organizationId, includeSupporters, MAX_ORG_DESCENDANT_DEPTH, cursorSubjectId,
                limit, shardIndex, shardCount, PageRequest.of(0, limit));
    }

    /**
     * 受信者総数（{@code COUNT(DISTINCT user_id)}）を返す（enqueue の自動シャード数算出用・CMP-001⑤）。
     * 母集団条件は実配信の keyset クエリと同一のものを共有し、カウントと配信の母集団を厳密一致させる。
     */
    @Override
    public long countRecipients(String scopeRef, boolean includeSupporters) {
        long organizationId = Long.parseLong(scopeRef);
        return userRoleRepository.countDistributionUserIdsForOrganizationRecursive(
                organizationId, includeSupporters, MAX_ORG_DESCENDANT_DEPTH);
    }
}
