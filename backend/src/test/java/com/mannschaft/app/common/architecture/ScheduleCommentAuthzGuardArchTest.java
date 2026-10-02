package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.16 予定コメントスレッド — 認可番人への適合を名指しで固定する ArchUnit テスト（試練）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §4.5.2 / §9.4 AC-33。</p>
 *
 * <h2>{@code AuthzControllerGuardArchTest} があるのに、なぜ別途これを置くのか</h2>
 * <p>{@code AuthzControllerGuardArchTest} は <b>{@code FreezingArchRule}</b> であり、
 * 「既存の違反集合に対する<b>増分</b>」を検出する番人である。新設 Controller が凍結ストアに
 * 載っていなければ確かに赤になるが、それは<b>クラスが存在して初めて</b>成立する検査であり、
 * <b>実装が丸ごと無い間は何も言わない</b>（＝試練の時点で red にならない）。</p>
 *
 * <p>本テストは対象クラスを<b>名指しで存在確認</b>し、その全エンドポイントが認可シグナルを
 * 満たすことを固定する。これにより「Controller を書いたが認可を忘れた」だけでなく
 * 「Controller をまだ書いていない」も同じ1本の赤で表現できる。</p>
 *
 * <h2>委譲先 Service 内部の認可は拾わないという性質</h2>
 * <p>番人の呼び出しグラフ探索は深さ 2 までで、{@code AccessControlService} /
 * {@code ContentVisibilityChecker} / {@code *AccessGuard} / {@code *AccessService} への到達を見る。
 * したがって Controller から遠い場所に認可を置くと合格できない。合格させるために
 * <b>認可を薄くするのではなく</b>、Controller から辿れる位置（{@code *AccessGuard} 等）へ
 * 認可を正しく置くこと。</p>
 */
@DisplayName("F03.16 予定コメント 認可番人適合テスト（試練）")
class ScheduleCommentAuthzGuardArchTest {

    private static final String CONTROLLER_FQN =
            "com.mannschaft.app.schedule.controller.ScheduleCommentController";

    /** 設計書 §4.1 が定めるエンドポイントの本数。 */
    private static final int EXPECTED_ENDPOINT_COUNT = 8;

    @Test
    @DisplayName("AC-33 ScheduleCommentController が存在し、その全エンドポイントが認可シグナルを持つ")
    void 予定コメントControllerの全エンドポイントが認可シグナルを持つ() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app.schedule");

        assertThat(imported.contain(CONTROLLER_FQN))
                .as("設計書 §4.1 の 8 エンドポイントを提供する %s が存在しない", CONTROLLER_FQN)
                .isTrue();

        var isEndpoint = ControllerEndpoints.areMappingEndpointsOfControllerClasses();
        List<JavaMethod> endpoints = imported.get(CONTROLLER_FQN).getMethods().stream()
                .filter(isEndpoint)
                .toList();

        assertThat(endpoints)
                .as("設計書 §4.1 は一覧・meta・返信一覧・メンション候補・POST・PATCH・DELETE・settings の "
                        + EXPECTED_ENDPOINT_COUNT + " 本を定める")
                .hasSize(EXPECTED_ENDPOINT_COUNT);

        List<String> unguarded = endpoints.stream()
                .filter(method -> !AuthzControllerGuardArchTest.hasAuthorizationSignal(method))
                .map(JavaMethod::getFullName)
                .toList();

        assertThat(unguarded)
                .as("認可シグナル（@PreAuthorize / 認可クラスへの到達 / 正規マーカー）を持たないエンドポイントは、"
                        + "読めない予定のコメントを読み書きできる穴になる")
                .isEmpty();
    }
}
