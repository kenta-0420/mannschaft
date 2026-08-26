package com.mannschaft.app.reservation.service;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定期予約（F03.4.5 §6.2 W2-5）の<b>構造的前提を機械的に守る番人テスト</b>（検分 MUST⑥）。
 *
 * <p>このリポジトリの認可番人の思想では <b>javadoc の警告文は番人ではない</b>。
 * 以下 2 つの前提はどちらも「破られた瞬間に静かに壊れる」種類のものなので、
 * コメントではなくテストで機械的に止める。</p>
 *
 * <h2>番人①: {@link ReservationRecurringService} に {@code @Transactional} を付けてはならない</h2>
 * <p>AC-5-5 は「週ごと独立トランザクション」を要求する。オーケストレーターに
 * {@code @Transactional} が付くと、内側の {@code @Transactional}（1 週ぶんの予約作成）から例外が
 * 抜けた時点で Spring が参加中トランザクションを rollback-only にマークし、外側が例外を握っても
 * 最終コミットが {@code UnexpectedRollbackException} で失敗する。すなわち
 * <b>1 週の失敗が全週を巻き込み、スキップ設計が実質無効化される</b>。
 * しかもこの破綻は「例外が起きた場合だけ」顕在化するため、正常系のテストでは検知できない。</p>
 *
 * <h2>番人②: {@code createReservationForSeries} の呼び出し元はオーケストレーターのみ</h2>
 * <p>{@link ReservationService#createReservationForSeries} は<b>認可ゲート（view ゲート）も
 * レートリミットも持たない public {@code @Transactional} メソッド</b>である。
 * これらは series 単位で 1 回だけ適用する設計（AC-5-11）のため意図的に外してあるが、
 * Controller や他ドメインがこのメソッドを直接呼ぶと<b>view ゲートとレートリミットを丸ごと迂回</b>できる。
 * 「共通ヘルパを一元化したが片方の経路が使っていない／別経路が生えた」は当リポジトリの典型事故
 * （{@code feedback_centralized_helper_not_proof_all_paths_use_it}）であり、
 * 呼び出し元をホワイトリストで固定する。</p>
 */
@DisplayName("定期予約 構造番人テスト（F03.4.5 §6.2 W2-5・検分 MUST⑥）")
class ReservationRecurringServiceArchGuardTest {

    /** 認可ゲートを持たない series 作成入口を呼んでよい唯一のクラス。 */
    private static final Set<String> ALLOWED_SERIES_CREATE_CALLERS =
            Set.of(ReservationRecurringService.class.getName());

    private static JavaClasses reservationClasses;

    @BeforeAll
    static void importClasses() {
        reservationClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    // ────────────────────────────────────────────────────────────
    // 番人①: オーケストレーターは非トランザクションでなければならない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("番人①: ReservationRecurringService に @Transactional を付けてはならない（週ごと独立tx）")
    void オーケストレーターは非トランザクションであること() {
        Class<?> orchestrator = ReservationRecurringService.class;

        assertThat(orchestrator.getAnnotation(Transactional.class))
                .as("クラスに @Transactional を付けると全メソッドに継承され、"
                        + "1 週の失敗が rollback-only マークで全週を巻き込む（AC-5-5 が静かに壊れる）")
                .isNull();

        List<String> transactionalMethods = java.util.Arrays.stream(orchestrator.getDeclaredMethods())
                .filter(m -> m.getAnnotation(Transactional.class) != null)
                .map(java.lang.reflect.Method::getName)
                .toList();

        assertThat(transactionalMethods)
                .as("メソッドにも @Transactional を付けてはならない。週ごとの作成は別 Bean "
                        + "（ReservationService.createReservationForSeries）のトランザクション境界に委譲する。"
                        + "違反メソッド=%s", transactionalMethods)
                .isEmpty();
    }

    @Test
    @DisplayName("番人①(裏付け): 週ごとの作成入口は @Transactional を持つ（独立tx の実体）")
    void 週ごとの作成入口はトランザクション境界であること() throws NoSuchMethodException {
        java.lang.reflect.Method forSeries = ReservationService.class.getMethod(
                "createReservationForSeries",
                Long.class, Long.class,
                com.mannschaft.app.reservation.dto.CreateReservationRequest.class,
                java.util.UUID.class);

        assertThat(forSeries.getAnnotation(Transactional.class))
                .as("非トランザクションのオーケストレーターから呼ばれて初めて新規 tx が開く。"
                        + "ここの @Transactional が外れると週ごと独立 tx が成立しない")
                .isNotNull();
    }

    // ────────────────────────────────────────────────────────────
    // 番人②: 認可ゲートなし入口の呼び出し元を固定する
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("番人②: createReservationForSeries を呼べるのは ReservationRecurringService のみ")
    void 認可ゲートなし入口の呼び出し元が限定されていること() {
        Set<String> callers = reservationClasses.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .flatMap(method -> method.getMethodCallsFromSelf().stream())
                .filter(call -> "createReservationForSeries".equals(call.getTarget().getName()))
                .filter(call -> call.getTargetOwner().getFullName()
                        .equals(ReservationService.class.getName()))
                .map(JavaMethodCall::getOriginOwner)
                .map(owner -> owner.getFullName())
                // 自クラス内の委譲（createReservation → 同名でない）は対象外。念のため自分自身は除く。
                .filter(name -> !name.equals(ReservationService.class.getName()))
                .collect(Collectors.toSet());

        assertThat(callers)
                .as("createReservationForSeries は view ゲートもレートリミットも持たない。"
                        + "許可外のクラスが呼ぶと認可とレートリミットを丸ごと迂回できる。"
                        + "実際の呼び出し元=%s / 許可=%s", callers, ALLOWED_SERIES_CREATE_CALLERS)
                .isSubsetOf(ALLOWED_SERIES_CREATE_CALLERS);

        assertThat(callers)
                .as("オーケストレーターからの呼び出しは実在しなければならない"
                        + "（メソッド名の変更等でこの番人が空振りするのを防ぐ）")
                .contains(ReservationRecurringService.class.getName());
    }

    @Test
    @DisplayName("番人②(裏付け): オーケストレーターは view ゲートとレートリミッタを自ら呼ぶ")
    void オーケストレーターが認可とレートリミットを適用していること() {
        JavaClasses orchestratorOnly = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ReservationRecurringService.class.getPackageName());

        Set<String> calledTargets = orchestratorOnly.stream()
                .filter(c -> c.getFullName().equals(ReservationRecurringService.class.getName()))
                .flatMap(c -> c.getMethods().stream())
                .flatMap((JavaMethod m) -> m.getMethodCallsFromSelf().stream())
                .map(call -> call.getTargetOwner().getSimpleName() + "#" + call.getTarget().getName())
                .collect(Collectors.toSet());

        assertThat(calledTargets)
                .as("series 単位で 1 回だけ認可ゲートを適用する構造であること（AC-5-11）")
                .contains("ReservationViewAccessGuard#assertCanView");
        assertThat(calledTargets)
                .as("series 単位で 1 回だけレートリミットを消費する構造であること（AC-5-11）")
                .contains("ReservationCreateRateLimiter#assertNotRateLimited");
    }
}
