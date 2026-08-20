package com.mannschaft.app.schedule.listener;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.membership.event.MembershipEndedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import com.mannschaft.app.team.event.TeamMemberRemovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * F03.19 §10.4【R9】カレンダーレイヤー設定のライフサイクル・フックの試練（AC-26 / AC-26b）。
 *
 * <h2>なぜ Docker 不要の単体テストで担保するのか</h2>
 * <p>設計書は AC-26 を BE-IT と指定するが、統合テストは Docker（Testcontainers）が無い環境で
 * <b>丸ごと skip され BUILD SUCCESSFUL の偽緑になる</b>。ここで検証したい命題は
 * 「<b>リスナーがどの削除キーを選ぶか</b>」であり、DB を立てなくても機械検証できる。
 * そこで Repository をインメモリの実データ集合で置き換え、派生クエリ
 * （{@code deleteByScopeTypeAndScopeId} / {@code deleteByUserId}）の絞り込み意味論を
 * テスト側で忠実に再現したうえで、<b>行が実際にどう残ったか</b>を突き合わせる。
 * リスナーが誤ったキー（他チーム ID・null・スコープ種別の取り違え）を渡せば、
 * 陰性対照の行が巻き添えで消えてテストが落ちる。</p>
 */
@DisplayName("F03.19 W1-e カレンダーレイヤー設定のライフサイクル（R9）")
class CalendarLayerLifecycleListenerTest {

    private static final long USER_A = 42L;
    private static final long USER_B = 43L;
    private static final long TEAM_10 = 10L;
    private static final long TEAM_11 = 11L;
    private static final long ORG_10 = 10L;
    private static final long ORG_20 = 20L;

    /** インメモリの設定行集合（Repository の実体の代役）。 */
    private List<UserCalendarLayerSettingEntity> rows;
    private UserCalendarLayerSettingRepository repository;
    private CalendarLayerLifecycleListener listener;

    @BeforeEach
    void setUp() {
        rows = new ArrayList<>(List.of(
                row(USER_A, "TEAM", TEAM_10),
                row(USER_B, "TEAM", TEAM_10),
                row(USER_A, "TEAM", TEAM_11),
                row(USER_A, "ORGANIZATION", ORG_10),
                row(USER_B, "ORGANIZATION", ORG_20),
                row(USER_A, "PERSONAL", 0L),
                row(USER_B, "PERSONAL", 0L)));
        repository = mock(UserCalendarLayerSettingRepository.class);

        // 派生クエリの絞り込み意味論を忠実に再現する（scope_type AND scope_id の一致行のみ物理削除）
        doAnswer(inv -> {
            String scopeType = inv.getArgument(0);
            Long scopeId = inv.getArgument(1);
            return removeIf(r -> Objects.equals(r.getScopeType(), scopeType)
                    && Objects.equals(r.getScopeId(), scopeId));
        }).when(repository).deleteByScopeTypeAndScopeId(anyString(), anyLong());

        // 同じく user_id 一致行のみ物理削除
        doAnswer(inv -> {
            Long userId = inv.getArgument(0);
            return removeIf(r -> Objects.equals(r.getUserId(), userId));
        }).when(repository).deleteByUserId(anyLong());

        // 委譲先 Bean は実物を使う（リスナー→Executor→Repository の経路そのものを踏む）。
        listener = new CalendarLayerLifecycleListener(new CalendarLayerCleanupExecutor(repository));
    }

    // ------------------------------------------------------------------
    // AC-26 チーム削除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("〔陽性〕チーム削除で、そのチームの設定行が全ユーザー分消える")
    void teamDeleted_removesRowsOfThatTeamForAllUsers() {
        listener.handleTeamDeleted(new TeamDeletedEvent(USER_A, TEAM_10));

        assertThat(keys()).doesNotContain("42/TEAM/10", "43/TEAM/10");
    }

    @Test
    @DisplayName("〔陰性対照〕チーム削除で、他チーム・同IDの組織・PERSONAL の行は消えない")
    void teamDeleted_doesNotRemoveOtherTeamsOtherScopeTypesOrPersonal() {
        listener.handleTeamDeleted(new TeamDeletedEvent(USER_A, TEAM_10));

        assertThat(keys()).containsExactlyInAnyOrder(
                "42/TEAM/11",            // 他チームの行は残る
                "42/ORGANIZATION/10",    // 同じ ID でもスコープ種別が違えば残る
                "43/ORGANIZATION/20",
                "42/PERSONAL/0",         // PERSONAL は無関係
                "43/PERSONAL/0");
    }

    // ------------------------------------------------------------------
    // AC-26 組織削除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("〔陽性〕組織削除で、その組織の設定行が消える")
    void organizationDeleted_removesRowsOfThatOrganization() {
        listener.handleOrganizationDeleted(new OrganizationDeletedEvent(USER_A, ORG_10));

        assertThat(keys()).doesNotContain("42/ORGANIZATION/10");
    }

    @Test
    @DisplayName("〔陰性対照〕組織削除で、同IDのチーム・他組織・PERSONAL の行は消えない")
    void organizationDeleted_doesNotRemoveSameIdTeamOrOtherOrganizations() {
        listener.handleOrganizationDeleted(new OrganizationDeletedEvent(USER_A, ORG_10));

        assertThat(keys()).containsExactlyInAnyOrder(
                "42/TEAM/10",            // scope_id が同じ 10 でもチームは残る（種別の取り違え検出）
                "43/TEAM/10",
                "42/TEAM/11",
                "43/ORGANIZATION/20",
                "42/PERSONAL/0",
                "43/PERSONAL/0");
    }

    // ------------------------------------------------------------------
    // AC-26 退会
    // ------------------------------------------------------------------

    @Test
    @DisplayName("〔陽性〕退会で、そのユーザーの設定行が全スコープ分消える")
    void userAnonymized_removesAllRowsOfThatUser() {
        listener.handleUserAnonymized(new UserAnonymizedEvent(USER_A, "old@example.com"));

        assertThat(keys()).doesNotContain(
                "42/TEAM/10", "42/TEAM/11", "42/ORGANIZATION/10", "42/PERSONAL/0");
    }

    @Test
    @DisplayName("〔陰性対照〕退会で、他ユーザーの設定行は消えない")
    void userAnonymized_doesNotRemoveOtherUsersRows() {
        listener.handleUserAnonymized(new UserAnonymizedEvent(USER_A, "old@example.com"));

        assertThat(keys()).containsExactlyInAnyOrder(
                "43/TEAM/10", "43/ORGANIZATION/20", "43/PERSONAL/0");
    }

    // ------------------------------------------------------------------
    // AC-26b【R9 の要】脱退では消さない
    // ------------------------------------------------------------------

    @Test
    @DisplayName("〔陰性・R9の要〕脱退系イベントは購読しない — 購読は削除・退会の3種のみ")
    void leaveEvents_areNotSubscribed_soRowsSurviveForRejoin() {
        Set<Class<?>> subscribed = subscribedEventTypes();

        // 脱退では行を残す（再加入で色が復活する）。購読していないことが実装そのもの。
        assertThat(subscribed)
                .doesNotContain(MembershipEndedEvent.class)
                .doesNotContain(TeamMemberRemovedEvent.class);

        // 将来「脱退でも消す」フックが足されたらここで落ちる（R9 の門番）
        assertThat(subscribed).containsExactlyInAnyOrder(
                TeamDeletedEvent.class,
                OrganizationDeletedEvent.class,
                UserAnonymizedEvent.class);
    }

    // ------------------------------------------------------------------
    // 切り離し（親の操作をロールバックさせない）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("〔切り離し〕削除に失敗しても例外を伝播させない（親のチーム削除は成功したまま）")
    void scopeCleanupFailure_doesNotEscapeToCaller() {
        doThrow(new RuntimeException("db down"))
                .when(repository).deleteByScopeTypeAndScopeId(anyString(), anyLong());

        assertThatCode(() -> listener.handleTeamDeleted(new TeamDeletedEvent(USER_A, TEAM_10)))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.handleOrganizationDeleted(
                new OrganizationDeletedEvent(USER_A, ORG_10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("〔切り離し〕退会時の削除に失敗しても例外を伝播させない（退会処理は成功したまま）")
    void withdrawalCleanupFailure_doesNotEscapeToCaller() {
        doThrow(new RuntimeException("db down")).when(repository).deleteByUserId(anyLong());

        assertThatCode(() -> listener.handleUserAnonymized(
                new UserAnonymizedEvent(USER_A, "old@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("〔切り離し・CMP-112〕委譲先 Bean のコミット完了例外も try の外へ漏らさない"
            + "（後続リスナーを巻き添えにしない）")
    void cleanupExecutorCompletionFailure_doesNotEscapeToCaller() {
        // REQUIRES_NEW を持つのは委譲先 Bean 側。そこが rollback-only でコミットに失敗すると
        // 例外はメソッドを抜けた後（プロキシのコミット時）に発生する。
        // リスナー側はトランザクション境界の外側なので、これも確実に捕まえられなければならない。
        CalendarLayerCleanupExecutor failing = mock(CalendarLayerCleanupExecutor.class);
        doThrow(new UnexpectedRollbackException("Transaction silently rolled back"))
                .when(failing).deleteScope(anyString(), anyLong());
        doThrow(new UnexpectedRollbackException("Transaction silently rolled back"))
                .when(failing).deleteByUser(anyLong());
        CalendarLayerLifecycleListener isolated = new CalendarLayerLifecycleListener(failing);

        assertThatCode(() -> isolated.handleTeamDeleted(new TeamDeletedEvent(USER_A, TEAM_10)))
                .doesNotThrowAnyException();
        assertThatCode(() -> isolated.handleOrganizationDeleted(
                new OrganizationDeletedEvent(USER_A, ORG_10)))
                .doesNotThrowAnyException();
        assertThatCode(() -> isolated.handleUserAnonymized(
                new UserAnonymizedEvent(USER_A, "old@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("〔経路の固定〕リスナーは Repository を直接持たず、委譲先 Bean 経由でのみ削除する")
    void listener_doesNotHoldRepositoryDirectly() {
        List<Class<?>> fieldTypes =
                Arrays.stream(CalendarLayerLifecycleListener.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getType)
                        .collect(Collectors.toList());

        assertThat(fieldTypes)
                .as("Repository を直接持つと REQUIRES_NEW を経由しない削除経路が生まれる")
                .doesNotContain(UserCalendarLayerSettingRepository.class)
                .contains(CalendarLayerCleanupExecutor.class);
    }

    @Test
    @DisplayName("〔切り離しの機構〕購読は AFTER_COMMIT・REQUIRES_NEW は委譲先 Bean が持つ（自クラスに付けない）")
    void allListenerMethods_useAfterCommit_andDelegateRequiresNewToSeparateBean() {
        List<Method> methods = listenerMethods();

        assertThat(methods).hasSize(3);
        for (Method m : methods) {
            // 親がロールバックしたら走らない（コミット成立後のみ）
            assertThat(m.getAnnotation(TransactionalEventListener.class).phase())
                    .as("%s の phase", m.getName())
                    .isEqualTo(TransactionPhase.AFTER_COMMIT);
            // 【CMP-112】REQUIRES_NEW を購読メソッド自身に付けてはならない。
            // プロキシの commit/rollback は「メソッドを抜けた後」に走るため、削除 SQL が失敗して
            // 新 TX が rollback-only になった場合、内部の catch が SQL 例外を捕まえても
            // 完了例外（UnexpectedRollbackException / TransactionSystemException）は try の外へ伝播する。
            // つまりベストエフォートの隔離が成立せず、同一イベントの後続リスナーを巻き添えにしうる。
            // 根治は「REQUIRES_NEW を別 Bean へ切り出し、その呼び出し全体を非トランザクションな側で捕捉する」。
            assertThat(m.getAnnotation(Transactional.class))
                    .as("%s に @Transactional を付けない（REQUIRES_NEW は委譲先 Bean が持つ）", m.getName())
                    .isNull();
        }

        // 委譲先 Bean（別クラス）が REQUIRES_NEW を宣言していること。
        // 同一クラス内のメソッド分割では自己呼び出しになりプロキシが挟まらないため解決しない。
        List<Method> requiresNewOnCollaborators =
                Arrays.stream(CalendarLayerLifecycleListener.class.getDeclaredFields())
                        .flatMap(f -> Arrays.stream(f.getType().getDeclaredMethods()))
                        .filter(mm -> mm.isAnnotationPresent(Transactional.class))
                        .filter(mm -> mm.getAnnotation(Transactional.class).propagation()
                                == Propagation.REQUIRES_NEW)
                        .collect(Collectors.toList());
        assertThat(requiresNewOnCollaborators)
                .as("REQUIRES_NEW を宣言した委譲先 Bean が注入されていること")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static UserCalendarLayerSettingEntity row(Long userId, String scopeType, Long scopeId) {
        UserCalendarLayerSettingEntity e = new UserCalendarLayerSettingEntity();
        e.setUserId(userId);
        e.setScopeType(scopeType);
        e.setScopeId(scopeId);
        e.setHidden(false);
        return e;
    }

    private int removeIf(Predicate<UserCalendarLayerSettingEntity> p) {
        int before = rows.size();
        rows.removeIf(p);
        return before - rows.size();
    }

    /** 残存行を {@code userId/scopeType/scopeId} のキー集合で表す。 */
    private List<String> keys() {
        return rows.stream()
                .map(r -> r.getUserId() + "/" + r.getScopeType() + "/" + r.getScopeId())
                .collect(Collectors.toList());
    }

    private static List<Method> listenerMethods() {
        return Arrays.stream(CalendarLayerLifecycleListener.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(TransactionalEventListener.class))
                .collect(Collectors.toList());
    }

    private static Set<Class<?>> subscribedEventTypes() {
        return listenerMethods().stream()
                .map(m -> m.getParameterTypes()[0])
                .collect(Collectors.toSet());
    }
}
