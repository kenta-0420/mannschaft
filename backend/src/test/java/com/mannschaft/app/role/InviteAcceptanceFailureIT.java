package com.mannschaft.app.role;

import com.mannschaft.app.chat.service.ChatMembershipInviteCardService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.role.service.MembershipInviteService;
import com.mannschaft.app.role.service.MembershipInviteTokenIssuer;
import com.mannschaft.app.role.service.SharedInviteTokenIssuer;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.service.MyScopeFolderService;
import com.mannschaft.app.team.TeamErrorCode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * CMP-260826-1457 W1 前段: 障害と部分失敗（AC F1・R1・R6・R7・R8・K1・M2・B-b1〜b3）、
 * および T6 と T7 の間に割り込む決定的な競合（C2・C3・C6 の release 版）と失効競合 C8。
 *
 * <p>止め所は既存 Bean の spy（{@link MembershipGrantService}・{@link MyScopeFolderService}・
 * {@link ChatMembershipInviteCardService}）と MySQL トリガで作る。新設型には触れない（反射で呼ぶ）。
 * spy を持つため専用 context になる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CMP-1457 招待承諾の障害・部分失敗・補償")
class InviteAcceptanceFailureIT extends InviteAcceptanceItSupport {

    @MockitoSpyBean private MembershipGrantService grantSpy;
    @MockitoSpyBean private MyScopeFolderService folderSpy;
    @MockitoSpyBean private ChatMembershipInviteCardService cardSpy;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MembershipInviteService membershipInvites;
    @Autowired private MembershipInviteTokenIssuer membershipIssuer;
    @Autowired private SharedInviteTokenIssuer sharedIssuer;

    /** プロセス停止の模擬。業務例外でも技術例外でもない。 */
    static final class SimulatedCrash extends Error {
        SimulatedCrash(String point) {
            super("試練: " + point + " で停止");
        }
    }

    private MembershipGrantService grant() {
        return AopTestUtils.getUltimateTargetObject(grantSpy);
    }

    private MyScopeFolderService folders() {
        return AopTestUtils.getUltimateTargetObject(folderSpy);
    }

    private void onGrantRole(Answer<?> answer) {
        doAnswer(answer).when(grant()).grantRole(anyString(), anyLong(), anyLong(), anyLong(), any(), anyString());
    }

    private static void registerSync(TransactionSynchronization sync) {
        TransactionSynchronizationManager.registerSynchronization(sync);
    }

    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private InviteTokenEntity tokenBy(Scope scope, Long createdBy, Integer maxUses, Long target) {
        return inTx(() -> tokens.saveAndFlush(token(scope, 0, null, maxUses, 0, target, null).toBuilder()
                .createdBy(createdBy).build()));
    }

    // ------------------------------------------------------------------
    // F1・R7・R8・K1: T7 の結果と release の可否
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("F1 red: T7 本体の BusinessException→release、回数戻り、user_roles・memberships 残らず、元例外を再送")
    void businessFailureInGrantReleases(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        BusinessException original = new BusinessException(RoleErrorCode.ROLE_002);
        onGrantRole(inv -> {
            inv.callRealMethod();
            throw original;
        });
        Throwable thrown = catching(() -> accept(token.getToken(), userId));
        assertThat(thrown).isSameAs(original);
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claimStatuses(token.getId())).containsExactly("RELEASED");
        assertThat(userRoleCount(userId, scope)).isZero();
        assertThat(membershipCount(userId, scope)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,BEFORE_COMMIT", "TEAM,TX_SYSTEM", "TEAM,UNEXPECTED_ROLLBACK",
            "ORGANIZATION,BEFORE_COMMIT", "ORGANIZATION,TX_SYSTEM", "ORGANIZATION,UNEXPECTED_ROLLBACK"})
    @DisplayName("R7 red: T7 のコミット成否不明（コミット時例外・rollback 失敗）→release しない、CLAIMED が残る")
    void unknownCommitOutcomeKeepsClaim(String type, String failure) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        onGrantRole(inv -> {
            switch (failure) {
                case "BEFORE_COMMIT" -> {
                    Object result = inv.callRealMethod();
                    registerSync(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("試練: T7 コミット時例外");
                        }
                    });
                    return result;
                }
                case "TX_SYSTEM" -> throw new TransactionSystemException("試練: rollback 失敗");
                default -> throw new UnexpectedRollbackException("試練: 予期しない rollback");
            }
        });
        Throwable thrown = catching(() -> accept(token.getToken(), userId));
        assertThat(thrown).isNotNull().isNotInstanceOf(BusinessException.class);
        assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(userRoleCount(userId, scope)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,LOCK_TIMEOUT", "TEAM,PESSIMISTIC", "ORGANIZATION,LOCK_TIMEOUT", "ORGANIZATION,PESSIMISTIC"})
    @DisplayName("R8 red: T7 本体の技術例外（ロック待ちタイムアウト等）→release しない、CLAIMED が残る")
    void technicalFailureKeepsClaim(String type, String failure) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        RuntimeException technical = "LOCK_TIMEOUT".equals(failure)
                ? new CannotAcquireLockException("試練: Lock wait timeout exceeded")
                : new PessimisticLockingFailureException("試練: 悲観ロック失敗");
        onGrantRole(inv -> {
            throw technical;
        });
        Throwable thrown = catching(() -> accept(token.getToken(), userId));
        assertThat(thrown).isSameAs(technical);
        assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("R7/R8 red: release しない技術例外では ERROR ログを残す")
    void technicalFailureIsLoggedAsError(CapturedOutput output) {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        onGrantRole(inv -> {
            throw new CannotAcquireLockException("試練: Lock wait timeout exceeded");
        });
        catching(() -> accept(token.getToken(), userId));
        UUID claim = claims(token.getId()).get(0).id();
        assertThat(output.getAll()).contains("ERROR").contains(claim.toString());
    }

    @ParameterizedTest
    @CsvSource({"TEAM,AFTER_T6", "TEAM,AFTER_T7", "TEAM,AFTER_T9",
            "ORGANIZATION,AFTER_T6", "ORGANIZATION,AFTER_T7", "ORGANIZATION,AFTER_T9"})
    @DisplayName("K1 red: 停止（T6 後／T7 後／T9 後）の最終状態と上限非超過")
    void crashAtEachStep(String type, String point) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = tokenBy(scope, admin, 1, null);
        Long userId = user();
        switch (point) {
            case "AFTER_T6" -> onGrantRole(inv -> {
                throw new SimulatedCrash(point);
            });
            case "AFTER_T7" -> onGrantRole(inv -> {
                Object result = inv.callRealMethod();
                registerSync(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        throw new SimulatedCrash(point);
                    }
                });
                return result;
            });
            default -> doAnswer(inv -> {
                throw new SimulatedCrash(point);
            }).when(folders()).findOrCreateDefaultInternal(anyLong(), any());
        }
        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            assertThat(catching(() -> accept(token.getToken(), userId))).isInstanceOf(SimulatedCrash.class);
            events = capture.events();
        }
        assertThat(usedCount(token.getId())).isEqualTo(1);
        switch (point) {
            case "AFTER_T6" -> {
                assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");
                assertThat(userRoleCount(userId, scope)).isZero();
                assertThat(membershipCount(userId, scope)).isZero();
            }
            case "AFTER_T7" -> {
                assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");
                assertThat(userRoleCount(userId, scope)).isEqualTo(1);
                assertThat(membershipCount(userId, scope)).isEqualTo(1);
                assertThat(events).as("membership JOINED のみ、招待監査なし")
                        .containsExactly("TEAM".equals(type) ? "TEAM:JOINED" : "ORGANIZATION:JOINED");
            }
            default -> {
                assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
                assertThat(userRoleCount(userId, scope)).isEqualTo(1);
                assertThat(folderPlacements(userId, scope)).isEmpty();
            }
        }
        Long newcomer = user();
        assertError(business(() -> accept(token.getToken(), newcomer)), RoleErrorCode.ROLE_003, null);
        assertThat(usedCount(token.getId())).as("上限非超過").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // R1・R6: release と recordJoined の冪等・不正入力
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R1 red: release 2回で1回分だけ戻る、GRANTED への release は無効")
    void releaseIsIdempotent(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        UUID claimed = insertClaim(token.getId(), user(), "CLAIMED", utcNow(), true);
        UUID granted = insertClaim(token.getId(), user(), "GRANTED", utcNow(), true);
        assertThat(usedCount(token.getId())).isEqualTo(2);

        releaseClaim(claimed);
        releaseClaim(claimed);
        assertThat(claimStatus(claimed)).isEqualTo("RELEASED");
        assertThat(usedCount(token.getId())).isEqualTo(1);

        catching(() -> releaseClaim(granted));
        assertThat(claimStatus(granted)).as("GRANTED は戻らない").isEqualTo("GRANTED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R1 red: claim と release の並行で used_count の更新が失われない（5巡）")
    void releaseAndClaimDoNotLoseUpdates(String type) throws Exception {
        Scope scope = scope(type);
        for (int round = 0; round < 5; round++) {
            InviteTokenEntity token = token(scope, 0, null, 2, 0, null, null);
            UUID stuck = insertClaim(token.getId(), user(), "CLAIMED", utcNow(), true);
            Long newcomer = user();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var releasing = executor.submit(() -> releaseClaim(stuck));
                var claiming = executor.submit(() -> accept(token.getToken(), newcomer));
                assertThat(outcome(releasing)).isNull();
                assertThat(outcome(claiming)).isNull();
            }
            assertThat(claimStatus(stuck)).isEqualTo("RELEASED");
            assertThat(usedCount(token.getId())).as("round " + round).isEqualTo(1);
            assertThat(claimStatuses(token.getId()).stream().filter("GRANTED"::equals)).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R1 red: release の2本目（used_count 減算）が失敗したら全巻き戻しで CLAIMED のまま")
    void releaseSecondStatementFailureRollsBack(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        UUID claimed = insertClaim(token.getId(), user(), "CLAIMED", utcNow(), true);
        String trigger = "trg_cmp1457_r1_" + token.getId();
        rootExecute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON invite_tokens FOR EACH ROW BEGIN "
                + "IF NEW.id = " + token.getId() + " AND NEW.used_count < OLD.used_count THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cmp1457 release failure'; END IF; END");
        try {
            assertThat(catching(() -> releaseClaim(claimed))).isNotNull();
        } finally {
            rootExecute("DROP TRIGGER IF EXISTS " + trigger);
        }
        assertThat(claimStatus(claimed)).isEqualTo("CLAIMED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("R6 red: 不存在 claim の release/recordJoined、RELEASED への recordJoined は IllegalStateException＋ERROR")
    void invalidClaimTransitionsFailLoudly(CapturedOutput output) {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        UUID missing = UUID.randomUUID();
        assertThat(catching(() -> releaseClaim(missing))).isInstanceOf(IllegalStateException.class);
        assertThat(catching(() -> recordJoined(missing))).isInstanceOf(IllegalStateException.class);
        UUID released = insertClaim(token.getId(), user(), "RELEASED", utcNow(), false);
        assertThat(catching(() -> recordJoined(released))).isInstanceOf(IllegalStateException.class);
        assertThat(claimStatus(released)).isEqualTo("RELEASED");
        assertThat(output.getAll()).contains("ERROR").contains(missing.toString()).contains(released.toString());
    }

    // ------------------------------------------------------------------
    // M2: 監視 gauge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("M2 red: gauge は閾値（10分）超過の CLAIMED 件数と最古 CLAIMED の滞留秒を出す")
    void staleClaimGauges() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 10, 0, null, null);
        double before = gauge(GAUGE_STALE_COUNT);
        insertClaim(token.getId(), user(), "CLAIMED", utcNow().minusMinutes(11), true);
        insertClaim(token.getId(), user(), "CLAIMED", utcNow().minusMinutes(45), true);
        insertClaim(token.getId(), user(), "CLAIMED", utcNow(), true);
        insertClaim(token.getId(), user(), "GRANTED", utcNow().minusHours(3), true);
        insertClaim(token.getId(), user(), "RELEASED", utcNow().minusHours(3), false);
        assertThat(gauge(GAUGE_STALE_COUNT) - before).as("閾値超過の CLAIMED だけを数える").isEqualTo(2.0);
        assertThat(gauge(GAUGE_OLDEST_AGE)).as("最古 CLAIMED の滞留秒").isGreaterThanOrEqualTo(45 * 60 - 5);
    }

    private double gauge(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        if (gauge == null) {
            return fail("未実装: gauge " + name + "（陣立て書 §9.6）");
        }
        return gauge.value();
    }

    // ------------------------------------------------------------------
    // B-b1〜b3: (b) 案1 配置失敗（部分成功）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"TEAM,FOLDER_GONE", "TEAM,FOLDER_TYPE_CHANGED", "TEAM,NOT_MEMBER", "TEAM,DEFAULT_RACE", "TEAM,DB_FAILURE",
            "ORGANIZATION,FOLDER_GONE", "ORGANIZATION,FOLDER_TYPE_CHANGED", "ORGANIZATION,NOT_MEMBER",
            "ORGANIZATION,DEFAULT_RACE", "ORGANIZATION,DB_FAILURE"})
    @DisplayName("B-b1 red: 外側TXなしで配置失敗→参加あり・回数+1・GRANTED・監査あり・配置なし・配置のエラー・再送 409")
    void placementFailureIsPartialSuccess(String type, String failure) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = tokenBy(scope, admin, 3, null);
        Long userId = user();
        Long folderId = null;
        RuntimeException injected = null;
        switch (failure) {
            case "FOLDER_GONE", "FOLDER_TYPE_CHANGED" -> {
                Long folder = folder(userId, type);
                folderId = folder;
                String sql = "FOLDER_GONE".equals(failure)
                        ? "UPDATE my_scope_folders SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?"
                        : "UPDATE my_scope_folders SET scope_type = '" + ("TEAM".equals(type) ? "ORGANIZATION" : "TEAM")
                                + "' WHERE id = ?";
                // T5 の事前確認は通り、T7（付与）の後でフォルダが消える・種類が変わる。
                onGrantRole(inv -> {
                    Object result = inv.callRealMethod();
                    jdbc.update(sql, folder);
                    return result;
                });
            }
            case "NOT_MEMBER" -> {
                injected = new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_MEMBER);
                RuntimeException e = injected;
                doAnswer(inv -> {
                    throw e;
                }).when(folders()).addItemWithAssignedVia(anyLong(), anyLong(), anyLong(), any());
            }
            case "DEFAULT_RACE" -> {
                injected = new DataIntegrityViolationException("試練: uq_msf_user_scope_default 同時作成");
                RuntimeException e = injected;
                doAnswer(inv -> {
                    throw e;
                }).when(folders()).findOrCreateDefaultInternal(anyLong(), any());
            }
            default -> {
                injected = new DataAccessResourceFailureException("試練: DB 障害");
                RuntimeException e = injected;
                doAnswer(inv -> {
                    throw e;
                }).when(folders()).addItemWithAssignedVia(anyLong(), anyLong(), anyLong(), any());
            }
        }
        Long chosenFolder = folderId;
        List<String> events;
        Throwable thrown;
        try (AuditCapture capture = capture(userId)) {
            thrown = catching(() -> accept(token.getToken(), userId, chosenFolder));
            events = capture.events();
        }
        if (injected != null) {
            assertThat(thrown).as("応答は配置のエラー").isSameAs(injected);
        } else {
            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isInstanceOf(ScopeFolderErrorCode.class));
        }
        assertPartialSuccess(scope, token, userId, events);
        assertThat(folderPlacements(userId, scope)).as("配置なし").isEmpty();
        assertError(business(() -> accept(token.getToken(), userId, null)), TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B-b2 red: 配置のコミット成否不明→応答はエラー、配置の有無は断定しない、他は B-b1 と同じ")
    void placementCommitOutcomeUnknown(String type) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = tokenBy(scope, admin, 3, null);
        Long userId = user();
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            registerSync(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalStateException("試練: 配置コミット成否不明");
                }
            });
            return result;
        }).when(folders()).addItemWithAssignedVia(anyLong(), anyLong(), anyLong(), any());
        List<String> events;
        Throwable thrown;
        try (AuditCapture capture = capture(userId)) {
            thrown = catching(() -> accept(token.getToken(), userId, null));
            events = capture.events();
        }
        assertThat(thrown).isNotNull();
        assertPartialSuccess(scope, token, userId, events);
    }

    private void assertPartialSuccess(Scope scope, InviteTokenEntity token, Long userId, List<String> events) {
        assertThat(userRoleCount(userId, scope)).as("参加あり").isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
        assertThat(usedCount(token.getId())).as("回数+1").isEqualTo(1);
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
        assertThat(events).as("監査あり").containsExactlyInAnyOrderElementsOf(successAudit(scope.type()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B-b3 red: 外側TXありで配置失敗→全体巻き戻り、何も残らない")
    void placementFailureInsideOuterTransactionRollsBackAll(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        BusinessException injected = new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_MEMBER);
        doAnswer(inv -> {
            throw injected;
        }).when(folders()).addItemWithAssignedVia(anyLong(), anyLong(), anyLong(), any());
        AtomicReference<Throwable> inner = new AtomicReference<>();
        catching(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            inner.set(catching(() -> accept(token.getToken(), userId)));
            status.setRollbackOnly();
        }));
        assertThat(inner.get()).isSameAs(injected);
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claims(token.getId())).isEmpty();
        assertThat(userRoleCount(userId, scope)).isZero();
        assertThat(membershipCount(userId, scope)).isZero();
        assertThat(folderPlacements(userId, scope)).isEmpty();
    }

    // ------------------------------------------------------------------
    // C2・C3・C6（決定版）: T6 通過後・T7 前に競合者が先に付与する
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"TEAM,SAME_TOKEN", "TEAM,OTHER_TOKEN", "TEAM,JOIN_REQUEST",
            "ORGANIZATION,SAME_TOKEN", "ORGANIZATION,OTHER_TOKEN", "ORGANIZATION,JOIN_REQUEST"})
    @DisplayName("C2/C3/C6 red: T7 で ALREADY_JOINED→T8 release で回数戻り、409 TEAM_003、招待監査なし")
    void competitorGrantsBetweenClaimAndGrant(String type, String competitor) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        InviteTokenEntity otherToken = token(scope, 0, null, 3, 0, null, null);
        Long userId = user();
        AtomicBoolean intercepted = new AtomicBoolean();
        onGrantRole(inv -> {
            if (intercepted.compareAndSet(false, true)) {
                Runnable rival = switch (competitor) {
                    case "SAME_TOKEN" -> () -> accept(token.getToken(), userId);
                    case "OTHER_TOKEN" -> () -> accept(otherToken.getToken(), userId);
                    default -> () -> grantSpy.grantMemberRole(type, scope.id(), userId, admin, "JOIN_REQUEST");
                };
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Throwable rivalError = outcome(executor.submit(rival));
                    if (rivalError != null) {
                        throw new AssertionError("競合者の付与が失敗した", rivalError);
                    }
                }
            }
            return inv.callRealMethod();
        });
        List<String> events;
        Throwable thrown;
        try (AuditCapture capture = capture(userId)) {
            thrown = catching(() -> accept(token.getToken(), userId));
            events = capture.events();
        }
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_003);
            assertThat(e.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
        });
        int expectedUsed = "SAME_TOKEN".equals(competitor) ? 1 : 0;
        assertThat(usedCount(token.getId())).as("自分の claim 分は戻る").isEqualTo(expectedUsed);
        List<String> mine = claimStatuses(token.getId());
        assertThat(mine).contains("RELEASED");
        assertThat(mine.stream().filter(s -> !"RELEASED".equals(s)).toList())
                .isEqualTo("SAME_TOKEN".equals(competitor) ? List.of("GRANTED") : List.of());
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
        // 監査は競合者の1回分のみ（release された側は招待監査を出さない）。
        long expectedInviteAudits = "JOIN_REQUEST".equals(competitor) ? 0 : 1;
        assertThat(events.stream().filter(e -> e.equals("TEAM".equals(type) ? "TEAM:INVITED" : "ORGANIZATION:JOINED"))
                .count()).isEqualTo("TEAM".equals(type) ? expectedInviteAudits : 1 + expectedInviteAudits);
    }

    // ------------------------------------------------------------------
    // C8: 失効5経路が古い used_count を書き戻さない
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"TEAM,REVOKE", "TEAM,DECLINE", "TEAM,MEMBERSHIP_REVOKE", "TEAM,MEMBERSHIP_COMPENSATION",
            "TEAM,SHARED_COMPENSATION", "ORGANIZATION,REVOKE", "ORGANIZATION,DECLINE",
            "ORGANIZATION,MEMBERSHIP_REVOKE", "ORGANIZATION,MEMBERSHIP_COMPENSATION", "ORGANIZATION,SHARED_COMPENSATION"})
    @DisplayName("C8 red: 外側で古い Entity をロード→別TXが claim 加算 commit→外側が失効→used_count を書き戻さない")
    void revocationDoesNotWriteBackStaleUsedCount(String type, String site) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        Long target = user();
        InviteTokenEntity token = tokenBy(scope, admin, 3, target);
        doReturn(true).when((ChatMembershipInviteCardService) AopTestUtils.getUltimateTargetObject(cardSpy))
                .hasInviteCard(anyLong(), anyLong());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(tokens.findById(token.getId()).orElseThrow().getUsedCount()).isZero();
            commitElsewhere("UPDATE invite_tokens SET used_count = used_count + 1 WHERE id = ?", token.getId());
            revokeVia(site, token, admin, target);
        });
        assertThat(usedCount(token.getId())).as(site + " は古い used_count を書き戻さない").isEqualTo(1);
        assertThat(revokedAt(token.getId())).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,REVOKE", "TEAM,DECLINE", "TEAM,MEMBERSHIP_REVOKE", "TEAM,MEMBERSHIP_COMPENSATION",
            "TEAM,SHARED_COMPENSATION", "ORGANIZATION,REVOKE", "ORGANIZATION,DECLINE",
            "ORGANIZATION,MEMBERSHIP_REVOKE", "ORGANIZATION,MEMBERSHIP_COMPENSATION", "ORGANIZATION,SHARED_COMPENSATION"})
    @DisplayName("C8 red: 外側で古い Entity をロード→別TXが失効 commit→外側の失効は既失効日時を上書きしない")
    void revocationKeepsCommittedRevocationTimestamp(String type, String site) {
        Scope scope = scope(type);
        Long admin = admin(scope);
        Long target = user();
        InviteTokenEntity token = tokenBy(scope, admin, 3, target);
        doReturn(true).when((ChatMembershipInviteCardService) AopTestUtils.getUltimateTargetObject(cardSpy))
                .hasInviteCard(anyLong(), anyLong());
        LocalDateTime firstRevocationUtc = LocalDateTime.of(2025, 2, 3, 4, 5, 6);
        catching(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(tokens.findById(token.getId()).orElseThrow().getRevokedAt()).isNull();
            commitElsewhere("UPDATE invite_tokens SET revoked_at = ?, used_count = used_count + 1 WHERE id = ?",
                    firstRevocationUtc, token.getId());
            revokeVia(site, token, admin, target);
        }));
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM invite_tokens WHERE id = ?", LocalDateTime.class, token.getId()))
                .isEqualTo(firstRevocationUtc);
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    private void revokeVia(String site, InviteTokenEntity token, Long admin, Long target) {
        switch (site) {
            case "REVOKE" -> inviteService.revokeInviteToken(token.getId(), admin);
            case "DECLINE" -> declineCompat(token.getToken(), target);
            case "MEMBERSHIP_REVOKE" -> membershipInvites.revokeMembershipInvite(1L, token.getId(), admin);
            case "MEMBERSHIP_COMPENSATION" -> membershipIssuer.revokeForCompensation(token.getId());
            default -> sharedIssuer.revokeForCompensation(token.getId());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void commitElsewhere(String sql, Object... args) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Throwable error = outcome(executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(s -> jdbc.update(sql, args));
            }));
            if (error != null) {
                throw new AssertionError("別TXの更新が失敗した", error);
            }
        }
    }

    /** トリガ作成は SUPER 権限が要るため、試験専用コンテナの root 接続で行う。 */
    private void rootExecute(String sql) {
        String url = MYSQL.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?") + "useSSL=false&allowPublicKeyRetrieval=true";
        try (var connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new AssertionError("試験用トリガの操作に失敗: " + sql, e);
        }
    }
}
