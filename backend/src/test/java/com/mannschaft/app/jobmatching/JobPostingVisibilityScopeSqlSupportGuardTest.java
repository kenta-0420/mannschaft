package com.mannschaft.app.jobmatching;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.mapping.JobMatchingVisibilityMapper;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.service.JobPostingService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-028 Phase C の「静かな破綻」再発防止番人。
 *
 * <p>背景: {@link VisibilityScope#CUSTOM_TEMPLATE} は行ごとの動的判定（テンプレート評価）が
 * 必要で SQL 述語に落とせないため、{@code JobPostingRepository#findVisibleByTeamId} /
 * {@code JobMatchingVisibilityMapper} は意図的に対応対象外としている
 * （{@code JobPostingService} の JavaDoc・{@code docs/features/F00_content_visibility_resolver.md}
 * 参照）。現状は {@link JobPostingService#MVP_ALLOWED_SCOPES}（書き込み許可値）が
 * {@code TEAM_MEMBERS} / {@code TEAM_MEMBERS_SUPPORTERS} の2値のみのため
 * {@code CUSTOM_TEMPLATE} は到達しないが、この2値は「たまたま安全」なだけで、
 * 依存関係を機械的に検証する仕組みは無かった。
 *
 * <p>将来 {@code MVP_ALLOWED_SCOPES} に {@code CUSTOM_TEMPLATE}（や、SQL 述語がまだ
 * 対応していない他の値）が追加された瞬間、その値で作成された求人は SQL の
 * {@code WHERE visibility_scope IN (...)} に載らず、<b>誰にも見えなくなる</b>。
 * しかもエラーも警告も出ない（fail-closed の静かな破綻）。本テストはこれを防ぐ。
 *
 * <p><b>SQL 述語が対応済みの値</b>（2026-08 時点、実装を読んで確認したもの）:
 * <ul>
 *   <li>{@code TEAM_MEMBERS} / {@code TEAM_MEMBERS_SUPPORTERS} / {@code ORGANIZATION_SCOPE} /
 *       {@code JOBBER_PUBLIC_BOARD} — {@code JobMatchingVisibilityMapper#toFunctional} が
 *       {@code resolveVisibleLevels} の可視ラダー集合から機能 enum へ逆写像し、
 *       {@code JobPostingRepository#findVisibleByTeamId} の {@code visibility_scope IN (...)}
 *       に渡る。</li>
 *   <li>{@code JOBBER_INTERNAL} — {@code resolveVisibleLevels} のラダー集合には現れない
 *       （行依存の CUSTOM 軸）が、{@code JobPostingRepository#findVisibleByTeamId} が
 *       {@code user_roles × roles} への {@code EXISTS} サブクエリを OR で直接組み込んでおり
 *       SQL 述語として対応済み（{@code JobPostingVisibilityResolver#evaluateCustom} と
 *       同一の判定をSQLへ翻訳したもの）。</li>
 * </ul>
 *
 * <p><b>未対応</b>: {@code CUSTOM_TEMPLATE} のみ。
 *
 * <h2>このテストが落ちたら</h2>
 * <p>{@code MVP_ALLOWED_SCOPES} に {@code CUSTOM_TEMPLATE}（本テストの
 * {@code SQL_PREDICATE_SUPPORTED_SCOPES} に含まれない値）を追加しようとしている。
 * その値の求人は一覧 API で誰にも表示されなくなる（fail-closed）。
 * 対応するには、先に {@code JobPostingRepository#findVisibleByTeamId} の SQL 述語と
 * {@code JobMatchingVisibilityMapper} にその値を表現する述語を実装し、本テストの
 * {@code SQL_PREDICATE_SUPPORTED_SCOPES} にその値を追加してから
 * {@code MVP_ALLOWED_SCOPES} を拡張すること。{@code CUSTOM_TEMPLATE} の場合の解法案は
 * {@code docs/features/F00_content_visibility_resolver.md}（CMP-028 Phase C 節）を参照。</p>
 */
class JobPostingVisibilityScopeSqlSupportGuardTest {

    /**
     * ラダー経由で SQL 述語（{@code visibilityScope IN :scopes}）に載る値。
     *
     * <p><b>手書きせず {@link JobMatchingVisibilityMapper#toFunctional} から機械的に導出する。</b>
     * 全 {@link StandardVisibility} を与えたときに逆写像が返す集合が、そのままラダー経由で
     * SQL に載り得る値の全体である。Mapper を変更すればこの集合も自動で追随するため、
     * 「実装を触らずテストの列挙だけ増やして緑にする」という抜け道を塞げる。</p>
     */
    private static final Set<VisibilityScope> LADDER_SUPPORTED_SCOPES =
            JobMatchingVisibilityMapper.toFunctional(EnumSet.allOf(StandardVisibility.class));

    /**
     * ラダーに載らないが、Repository が<b>個別の SQL 述語</b>で対応している値。
     *
     * <p>ここだけは機械的に導出できない（{@code @Query} の本文と値の対応は静的解析でしか
     * 追えない）ため手書きである。追記する際は、**必ず対応する述語を実装してから**にすること。
     * 実装せずにここへ足せば番人は緑になるが、その値の求人は一覧から静かに消える。</p>
     *
     * <p>現在の唯一の該当値 {@link VisibilityScope#JOBBER_INTERNAL} は
     * {@code JobPostingRepository#findVisibleByTeamId} の {@code user_roles × roles} への
     * {@code EXISTS} サブクエリで対応済み。</p>
     */
    private static final Set<VisibilityScope> INDIVIDUALLY_SQL_SUPPORTED_SCOPES =
            EnumSet.of(VisibilityScope.JOBBER_INTERNAL);

    /** SQL 述語が対応済みの {@link VisibilityScope} 全体（ラダー由来 ∪ 個別述語）。 */
    private static final Set<VisibilityScope> SQL_PREDICATE_SUPPORTED_SCOPES =
            EnumSet.copyOf(concat(LADDER_SUPPORTED_SCOPES, INDIVIDUALLY_SQL_SUPPORTED_SCOPES));

    private static Set<VisibilityScope> concat(Set<VisibilityScope> a, Set<VisibilityScope> b) {
        Set<VisibilityScope> merged = EnumSet.noneOf(VisibilityScope.class);
        merged.addAll(a);
        merged.addAll(b);
        return merged;
    }

    @Test
    void mvpAllowedScopesMustBeSubsetOfSqlPredicateSupportedScopes() throws Exception {
        Set<VisibilityScope> mvpAllowedScopes = readMvpAllowedScopes();

        // 自己検証: 前提が壊れていないか（走査が空になっていないか等）を先に確認する。
        // JobPostingVisibilityScopeSqlSupportGuardTest 自身が「常に0件」で偽の安全を
        // 報告しないよう、両集合が空でないことを保証する。
        assertThat(mvpAllowedScopes)
                .as("JobPostingService.MVP_ALLOWED_SCOPES の読み取りに失敗している可能性がある"
                        + "（リフレクションでの参照先フィールド名変更などを疑うこと）")
                .isNotEmpty();
        assertThat(SQL_PREDICATE_SUPPORTED_SCOPES).isNotEmpty();
        // VisibilityScope 側にも値があるはずで、両集合が enum 全体を network out していないか確認。
        assertThat(VisibilityScope.values()).isNotEmpty();
        // ラダー集合は Mapper から機械的に導出しているため、Mapper の壊れ（全 case 削除など）で
        // 空になると「対応済みが何も無い」状態で誤検知する。前提の崩壊として先に捕まえる。
        assertThat(LADDER_SUPPORTED_SCOPES)
                .as("JobMatchingVisibilityMapper.toFunctional が 1 値も返さない"
                        + "（Mapper の実装が壊れている可能性。SQL 対応値ゼロとは別事象である）")
                .isNotEmpty();

        Set<VisibilityScope> unsupported = EnumSet.copyOf(mvpAllowedScopes);
        unsupported.removeAll(SQL_PREDICATE_SUPPORTED_SCOPES);

        assertThat(unsupported)
                .as(() -> """
                        JobPostingService.MVP_ALLOWED_SCOPES に、SQL 述語がまだ対応していない \
                        VisibilityScope が含まれています: %s

                        [何が起きるか] JobPostingRepository#findVisibleByTeamId の \
                        WHERE visibility_scope IN (...) はこの値を認識しないため、この公開範囲で \
                        作成された求人は一覧 API から誰にも見えなくなります（エラーも警告も出ない \
                        fail-closed の静かな破綻）。

                        [どう直すか] ※ 順序が重要。3 を先にやると番人は緑になるが破綻は残る。
                          1. JobPostingRepository#findVisibleByTeamId に、この値を対象にする \
                        SQL 述語を実装する。ラダーに載る値なら \
                        JobMatchingVisibilityMapper#toFunctional に追加すれば \
                        LADDER_SUPPORTED_SCOPES が自動で追随する（本テストの変更は不要）。\
                        ラダーに載らない値なら JOBBER_INTERNAL と同様の EXISTS サブクエリを実装し、\
                        INDIVIDUALLY_SQL_SUPPORTED_SCOPES に追加する。
                          2. CUSTOM_TEMPLATE の場合は F00 設計書 §10.6 を必ず読むこと。\
                        評価は「閲覧者 × テンプレートID × 行の作者」の 3 項関数であり \
                        （AbstractContentVisibilityResolver が row.authorUserId() を渡している）、\
                        visibility_template_id IN (...) だけでは過剰許可／誤拒否になる。\
                        さらに job_postings には visibility_template_id 列自体が存在しないため \
                        migration・Entity・作成更新経路の追加が前提作業として要る。
                          3. 実装を伴わずに本テストの集合だけを増やさないこと。\
                        それは番人を黙らせるだけで、この値の求人は依然として一覧から消える。
                          4. 実装と検証（許可側・拒否側の両方を実 DB で）を済ませてから \
                        MVP_ALLOWED_SCOPES を拡張する。
                        """.formatted(unsupported))
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Set<VisibilityScope> readMvpAllowedScopes() throws Exception {
        Field field = JobPostingService.class.getDeclaredField("MVP_ALLOWED_SCOPES");
        field.setAccessible(true);
        return (Set<VisibilityScope>) field.get(null);
    }
}
