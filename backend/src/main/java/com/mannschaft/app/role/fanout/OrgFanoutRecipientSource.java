package com.mannschaft.app.role.fanout;

import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientRowMapper;
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
     * 受信者ページを 1 チャンク供給する（Issue #2871・単一メソッド化）。
     *
     * <p>{@code shardCount <= 1} は従来の非シャード版クエリをそのまま使い（{@code MOD} 述語を挟まない）、
     * {@code shardCount > 1} のみ {@code MOD(user_id, shardCount) = shardIndex} 述語を足したシャード版を引く。
     * 母集団条件・SUPPORTER 除外・keyset・枝内 {@code LIMIT} は両者で共有（差分は MOD 述語 1 行のみ）。</p>
     *
     * <p>{@code chunk}（枝内の打ち切り件数）には外側のページサイズと同じ値を渡す。各枝から chunk 件ずつ
     * 取れば和集合の先頭 chunk 件は必ずその中に含まれる（詳細はリポジトリ側 javadoc）。</p>
     */
    @Override
    public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
        // scope_ref には組織 ID を文字列で格納しているため long へ復元する（多型スコープ参照）。
        long organizationId = Long.parseLong(request.scopeRef());
        int limit = request.limit();
        if (request.isSingleShard()) {
            return FanoutRecipientRowMapper.toRecipients(
                    userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                            organizationId, request.includeSupporters(), MAX_ORG_DESCENDANT_DEPTH,
                            request.cursorSubjectId(), limit, PageRequest.of(0, limit)));
        }
        return FanoutRecipientRowMapper.toRecipients(
                userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeysetSharded(
                        organizationId, request.includeSupporters(), MAX_ORG_DESCENDANT_DEPTH,
                        request.cursorSubjectId(), limit,
                        request.shardIndex(), request.shardCount(), PageRequest.of(0, limit)));
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
