package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.DataSourceType;
import com.mannschaft.app.config.ReplicaRoutingAspect;
import com.mannschaft.app.organization.service.OrganizationAffiliationPortAdapter;
import com.mannschaft.app.team.service.TeamOrgInviteCommandService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F01.2.1 2-D 検分5巡目: 閉鎖の状態・世代など<b>判定に使う</b>読み取りが、レプリカではなくプライマリへ振られることの検証。
 *
 * <p>{@link ReplicaRoutingAspect} は {@code @Transactional(readOnly = true)} をレプリカへ振る。レプリカは複製遅延で古い
 * 閉鎖の世代を返しうるので、それで判定すると閉鎖前の招待を ACTIVE にしたり、解除後の PENDING を消したりしうる。
 * 実メソッドの {@code @Transactional} を使って本物のアスペクトを通し、進入中のルーティング先を観測する
 * （{@code ReplicaRoutingAspectTest} と同じ作法）。外側がレプリカ指定でも、進入中はプライマリになることも確かめる。</p>
 */
@DisplayName("F01.2.1 2-D 閉鎖の判定に使う読み取りはプライマリへ振られる")
class TeamOrgClosureReadRoutingTest {

    private final ReplicaRoutingAspect aspect = new ReplicaRoutingAspect();

    @AfterEach
    void tearDown() {
        DataSourceContextHolder.clear();
    }

    static Stream<Method> judgmentReads() throws NoSuchMethodException {
        return Stream.of(
                // 承諾・片付けが加盟行のロックの後に読む組織の最新の閉鎖の状態（REQUIRES_NEW）
                OrganizationAffiliationPortAdapter.class.getMethod("findClosureStateIncludingDeleted", Long.class),
                // 回復バッチの候補判定
                OrganizationAffiliationPortAdapter.class.getMethod("findClosureStatesIncludingDeleted",
                        Collection.class),
                // 招待の作成で加盟行に記録する組織の世代・承諾の前段の状態確認
                OrganizationAffiliationPortAdapter.class.getMethod("findAffiliationState", Long.class),
                // 招待・承諾のグループの生存判定
                OrganizationAffiliationPortAdapter.class.getMethod("isAliveGroupOfOrganization", Long.class,
                        UUID.class),
                // 招待のコミット後の読み直し（共有ロック）
                OrganizationAffiliationPortAdapter.class.getMethod("recheckAffiliationStateLocked", Long.class),
                // 孤児 group_id の修復バッチ（7-A）のグループの生存判定（レプリカ遅延で新しいグループを削除済みと見誤らない）
                OrganizationAffiliationPortAdapter.class.getMethod("findAliveGroupOrganizationIds", Collection.class),
                // 承諾の前段（招待行の判定表）
                TeamOrgInviteCommandService.class.getMethod("findPendingInvite", Long.class, Long.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("judgmentReads")
    @DisplayName("readOnly ではなく、外側がレプリカ指定でも進入中はプライマリ")
    void routesToPrimary(Method method) throws Throwable {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).as("メソッドレベルの @Transactional（アスペクトの対象）").isNotNull();
        assertThat(transactional.readOnly()).as("readOnly だとレプリカへ振られる").isFalse();

        // 外側の読み取りがレプリカを指定している状態から呼ばれても、進入中はプライマリ
        DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);
        AtomicReference<DataSourceType> captured = new AtomicReference<>();
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.proceed()).thenAnswer(inv -> {
            captured.set(DataSourceContextHolder.getDataSourceType());
            return null;
        });

        aspect.routeDataSource(pjp);

        assertThat(captured.get()).isEqualTo(DataSourceType.PRIMARY);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("承諾・片付けの再確認は呼び出し側の tx に参加しない新しい tx（REQUIRES_NEW）のまま")
    void closureStateReadKeepsRequiresNew() throws NoSuchMethodException {
        Transactional transactional = OrganizationAffiliationPortAdapter.class
                .getMethod("findClosureStateIncludingDeleted", Long.class).getAnnotation(Transactional.class);
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
