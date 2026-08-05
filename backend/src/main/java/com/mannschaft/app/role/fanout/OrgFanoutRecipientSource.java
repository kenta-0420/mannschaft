package com.mannschaft.app.role.fanout;

import com.mannschaft.app.notification.fanout.FanoutRecipientSource;

import java.util.List;

/**
 * ORGANIZATION スコープの受信者ソース（fan-out 抜本改修 Wave-2・ORG 耐久 fan-out）の<b>骨格スタブ</b>。
 *
 * <h2>本ファイルは試練（第二陣）の骨格であり、本実装は第三陣（出陣）の担当</h2>
 * <p>現状は {@link #nextPage} が {@link UnsupportedOperationException} を投げるだけの空実装であり、
 * かつ Spring Bean（{@code @Component}）として登録していない。したがって
 * {@code FanoutRecipientSourceRegistry.resolve("ORGANIZATION")} は<b>空</b>を返し、受信者供給／配信を叩く
 * 受け入れ条件は<b>必ず失敗（red）</b>する。第三陣は本クラスに {@code @Component} を付与し、
 * {@link com.mannschaft.app.role.repository.UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset}
 * を用いた実装で green 化する（併せてジョブの {@code include_supporters} / {@code maxDepth} を配線する）。</p>
 *
 * <h2>配置ドメイン（越境 Repository 依存の解消・D-5 番人）</h2>
 * <p>受信者解決は {@code UserRoleRepository}（role ドメイン）を引くため、本実装は<b>role ドメイン</b>
 * （{@code com.mannschaft.app.role.fanout}）に置く。notification / organization ドメインに置くと
 * role ドメインの Repository への越境依存となり {@code CrossDomainRepositoryDependencyArchTest}（D-5）が
 * fail する。共有契約 {@link FanoutRecipientSource} は notification/fanout に残し、role 側が実装する
 * （依存性逆転・TEAM 版 {@code TeamFanoutRecipientSource} と同型）。</p>
 */
public class OrgFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー。ORGANIZATION スコープの戦略キー（ジョブ表 {@code scope_type} と一致）。 */
    public static final String SCOPE_TYPE = "ORGANIZATION";

    /** ORG 再帰展開のサイクル防止上限（第三陣が実装で使用する想定値）。 */
    public static final int MAX_DEPTH = 32;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit) {
        // 第三陣（出陣）で UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset を用いて実装する。
        // 試練段階では未実装ゆえ例外を投げ、受信者供給／配信の AC を red にする。
        throw new UnsupportedOperationException(
                "OrgFanoutRecipientSource.nextPage は第三陣（出陣）で実装する（fan-out Wave-2 ORG 耐久 fan-out）");
    }
}
