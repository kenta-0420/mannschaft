package com.mannschaft.app.jobmatching;

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
     * SQL 述語（{@code JobPostingRepository#findVisibleByTeamId} +
     * {@code JobMatchingVisibilityMapper}）が対応済みの {@link VisibilityScope} 集合。
     * 新しい値を SQL 側で対応させたら、ここに追記すること。
     */
    private static final Set<VisibilityScope> SQL_PREDICATE_SUPPORTED_SCOPES = EnumSet.of(
            VisibilityScope.TEAM_MEMBERS,
            VisibilityScope.TEAM_MEMBERS_SUPPORTERS,
            VisibilityScope.ORGANIZATION_SCOPE,
            VisibilityScope.JOBBER_PUBLIC_BOARD,
            VisibilityScope.JOBBER_INTERNAL
    );

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

                        [どう直すか]
                          1. JobPostingRepository#findVisibleByTeamId に、この値を対象にする \
                        SQL 述語（JobMatchingVisibilityMapper#toFunctional への追加、または \
                        JOBBER_INTERNAL と同様の EXISTS サブクエリの追加）を実装する。
                          2. CUSTOM_TEMPLATE の場合は、docs/features/F00_content_visibility_resolver.md \
                        の CMP-028 Phase C 節に記載した解法（閲覧可能テンプレートID集合を \
                        resolveVisibleLevels と同じ手で先に求め、\
                        visibility_template_id IN (...) に落とす）を参照して実装する。
                          3. 実装後、本テストの SQL_PREDICATE_SUPPORTED_SCOPES にその値を追加する。
                          4. その上で MVP_ALLOWED_SCOPES を拡張する。
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
