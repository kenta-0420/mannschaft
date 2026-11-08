package com.mannschaft.app.role;

import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.event.UserRegisteredEvent;
import com.mannschaft.app.auth.service.AuthRegistrationService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMP-260826-1457 W1 前段: 承諾・辞退の外部挙動パリティ（AC B1・B2・B3・D1・F2・M1）。
 *
 * <p>「特性」と付いた試験は現行（main）の外部挙動を固定するもので main でも緑が正しい（承諾は Facade があれば Facade、
 * 無ければ現行 InviteService を呼ぶ）。「red」と付いた試験は新設の claims／Facade を要求し main では落ちる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-1457 招待承諾・辞退の外部挙動パリティ")
class InviteAcceptanceParityIT extends InviteAcceptanceItSupport {

    @Autowired private AuthRegistrationService registration;

    // ------------------------------------------------------------------
    // B1: エラー表（特性）
    // ------------------------------------------------------------------

    private record Case(InviteTokenEntity token, Long userId, Long folderId) { }

    private void assertNoSideEffect(Scope scope, Case c, int usedBefore) {
        assertThat(usedCount(c.token().getId())).as("used_count 不変").isEqualTo(usedBefore);
        assertThat(claimCountOrZero(c.token().getId())).as("claims なし").isZero();
        assertThat(userRoleCount(c.userId(), scope)).as("user_roles なし").isZero();
        assertThat(membershipCount(c.userId(), scope)).as("memberships なし").isZero();
        assertThat(folderPlacements(c.userId(), scope)).as("フォルダ項目なし").isEmpty();
    }

    private void expect(Scope scope, Case c, ErrorCode code, HttpStatus override) {
        int before = usedCount(c.token().getId());
        BusinessException e = business(() -> acceptCompat(c.token().getToken(), c.userId(), c.folderId()));
        assertError(e, code, override);
        assertNoSideEffect(scope, c, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 非ACTIVE ユーザーは ROLE_002、副作用なし")
    void inactiveUserIsRejected(String type) {
        Scope scope = scope(type);
        expect(scope, new Case(token(scope, 0), inactiveUser("FROZEN"), null), RoleErrorCode.ROLE_002, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 不在トークンは ROLE_002")
    void unknownToken(String type) {
        Long userId = user();
        BusinessException e = business(() -> acceptCompat(UUID.randomUUID().toString(), userId, null));
        assertError(e, RoleErrorCode.ROLE_002, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ?", Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 上限到達は ROLE_003、期限切れ・失効は ROLE_002")
    void invalidTokens(String type) {
        Scope scope = scope(type);
        expect(scope, new Case(token(scope, 0, null, 1, 1, null, null), user(), null), RoleErrorCode.ROLE_003, null);
        expect(scope, new Case(token(scope, 0, LocalDateTime.now().minusMinutes(1), null, 0, null, null), user(), null),
                RoleErrorCode.ROLE_002, null);
        expect(scope, new Case(token(scope, 0, null, null, 0, null, LocalDateTime.now().minusMinutes(1)), user(), null),
                RoleErrorCode.ROLE_002, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 宛先違いは 403 ROLE_009、特権ロールは 422 ROLE_009")
    void targetMismatchAndPrivilegedRole(String type) {
        Scope scope = scope(type);
        Long someoneElse = user();
        expect(scope, new Case(token(scope, 0, null, 1, 0, someoneElse, null), user(), null),
                RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN);

        Long adminRoleId = roleId(scope, "ADMIN", 2);
        InviteTokenEntity privileged = inTx(() -> tokens.saveAndFlush(token(scope, 0).toBuilder().id(null)
                .token(UUID.randomUUID().toString()).roleId(adminRoleId).build()));
        expect(scope, new Case(privileged, user(), null), RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 閉鎖世代が古いトークンは ROLE_002")
    void closedGeneration(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0);
        closeScope(scope);
        expect(scope, new Case(token, user(), null), RoleErrorCode.ROLE_002, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: ブロック中は TEAM_004")
    void blocked(String type) {
        Scope scope = scope(type);
        Long userId = user();
        block(scope, userId, admin(scope));
        expect(scope, new Case(token(scope, 0), userId, null), TeamErrorCode.TEAM_004, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 在籍済みは 409 TEAM_003、回数・在籍は不変")
    void alreadyJoined(String type) {
        Scope scope = scope(type);
        Long userId = user();
        InviteTokenEntity first = token(scope, 0);
        acceptCompat(first.getToken(), userId, null);
        InviteTokenEntity second = token(scope, 0);
        BusinessException e = business(() -> acceptCompat(second.getToken(), userId, null));
        assertError(e, TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
        assertThat(usedCount(second.getId())).isZero();
        assertThat(claimCountOrZero(second.getId())).isZero();
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: フォルダ不存在は SCOPE_FOLDER_NOT_FOUND、種類違いは SCOPE_FOLDER_TYPE_MISMATCH（参加も残らない）")
    void invalidFolder(String type) {
        Scope scope = scope(type);
        Long userId = user();
        expect(scope, new Case(token(scope, 0), userId, Long.MAX_VALUE - 7), ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND, null);
        Long otherUsersFolder = folder(user(), type);
        expect(scope, new Case(token(scope, 0), userId, otherUsersFolder), ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND, null);
        Long mismatched = folder(userId, "TEAM".equals(type) ? "ORGANIZATION" : "TEAM");
        expect(scope, new Case(token(scope, 0), userId, mismatched), ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 無効理由が重なったときの優先順位（§6）")
    void errorPriority(String type) {
        Scope scope = scope(type);
        Long blockedBy = admin(scope);

        Long expiredAndBlocked = user();
        block(scope, expiredAndBlocked, blockedBy);
        expect(scope, new Case(token(scope, 0, LocalDateTime.now().minusMinutes(1), null, 0, null, null), expiredAndBlocked, null),
                RoleErrorCode.ROLE_002, null);

        Long exhaustedBadFolder = user();
        expect(scope, new Case(token(scope, 0, null, 1, 1, null, null), exhaustedBadFolder, Long.MAX_VALUE - 7),
                RoleErrorCode.ROLE_003, null);

        Long mismatchBlocked = user();
        block(scope, mismatchBlocked, blockedBy);
        expect(scope, new Case(token(scope, 0, null, 1, 0, user(), null), mismatchBlocked, null),
                RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN);

        Long closedBlocked = user();
        block(scope, closedBlocked, blockedBy);
        InviteTokenEntity stale = token(scope, 0);
        Scope closedScope = scope;
        // 閉鎖 → ブロックより先に ROLE_002（ブロックは 7 番目）。
        jdbc.update("UPDATE " + closedScope.table() + " SET closure_generation = 1 WHERE id = ?", closedScope.id());
        try {
            expect(scope, new Case(stale, closedBlocked, null), RoleErrorCode.ROLE_002, null);
        } finally {
            jdbc.update("UPDATE " + closedScope.table() + " SET closure_generation = 0, archived_at = NULL WHERE id = ?",
                    closedScope.id());
        }

        Long blockedBadFolder = user();
        block(scope, blockedBadFolder, blockedBy);
        expect(scope, new Case(token(scope, 0), blockedBadFolder, Long.MAX_VALUE - 7), TeamErrorCode.TEAM_004, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 在籍済み＋不正フォルダは 409 TEAM_003（在籍判定がフォルダより先）")
    void alreadyJoinedWinsOverBadFolder(String type) {
        Scope scope = scope(type);
        Long userId = user();
        acceptCompat(token(scope, 0).getToken(), userId, null);
        InviteTokenEntity second = token(scope, 0);
        BusinessException e = business(() -> acceptCompat(second.getToken(), userId, Long.MAX_VALUE - 7));
        assertError(e, TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
        assertThat(usedCount(second.getId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B1 特性: 期限直前・無期限・上限なしは成功し used_count が 1 増える")
    void boundarySuccesses(String type) {
        Scope scope = scope(type);
        InviteTokenEntity nearExpiry = token(scope, 0, LocalDateTime.now().plusSeconds(30), 1, 0, null, null);
        acceptCompat(nearExpiry.getToken(), user(), null);
        assertThat(usedCount(nearExpiry.getId())).isEqualTo(1);

        InviteTokenEntity unlimited = token(scope, 0, null, null, 5, null, null);
        acceptCompat(unlimited.getToken(), user(), null);
        acceptCompat(unlimited.getToken(), user(), null);
        assertThat(usedCount(unlimited.getId())).isEqualTo(7);
    }

    // ------------------------------------------------------------------
    // B2: 成功（特性＋red）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"TEAM,DEFAULT", "TEAM,INVITE", "ORGANIZATION,DEFAULT", "ORGANIZATION,INVITE"})
    @DisplayName("B2 特性: 新規成功で used_count+1、user_roles・memberships 各1件、配置、監査（TEAM JOINED+INVITED／ORG JOINED×2）")
    void successCharacteristic(String type, String via) {
        Scope scope = scope(type);
        Long userId = user();
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Long folderId = "INVITE".equals(via) ? folder(userId, type) : null;
        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            acceptCompat(token.getToken(), userId, folderId);
            events = capture.events();
        }
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
        assertThat(folderPlacements(userId, scope)).containsExactly(via);
        assertThat(events).containsExactlyInAnyOrderElementsOf(successAudit(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B2 red: 新規成功で claim が1件 GRANTED になる")
    void successRecordsGrantedClaim(String type) {
        Scope scope = scope(type);
        Long userId = user();
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        accept(token.getToken(), userId);
        List<Claim> claims = claims(token.getId());
        assertThat(claims).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("GRANTED");
            assertThat(c.userId()).isEqualTo(userId);
            assertThat(c.id().version()).as("claims.id は UUIDv7").isEqualTo(7);
        });
    }

    @ParameterizedTest
    @CsvSource({"TEAM,MEMBERSHIP_ONLY", "TEAM,SUPPORTER", "TEAM,USER_ROLE_ONLY",
            "ORGANIZATION,MEMBERSHIP_ONLY", "ORGANIZATION,SUPPORTER", "ORGANIZATION,USER_ROLE_ONLY"})
    @DisplayName("B2 特性: memberships のみ・SUPPORTER・user_roles のみ残存は 409、回数不変、claim なし、招待監査なし")
    void partialMembershipIsConflict(String type, String shape) {
        Scope scope = scope(type);
        Long userId = user();
        switch (shape) {
            case "MEMBERSHIP_ONLY" -> insertMembership(scope, userId, "MEMBER");
            case "SUPPORTER" -> insertMembership(scope, userId, "SUPPORTER");
            default -> insertUserRole(scope, userId);
        }
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            BusinessException e = business(() -> acceptCompat(token.getToken(), userId, null));
            assertError(e, TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
            events = capture.events();
        }
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claimCountOrZero(token.getId())).isZero();
        assertThat(inviteAuditCount(type, events)).isZero();
        assertThat(events).doesNotContain("TEAM:INVITED");
    }

    // ------------------------------------------------------------------
    // B3: 基数 b（特性）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B3 特性: (i) b=2,max=3 はあと1件だけ (ii) b=max は ROLE_003 (iii) b>max は ROLE_003 で値不変")
    void baselineUsedCount(String type) {
        Scope scope = scope(type);
        InviteTokenEntity belowMax = token(scope, 0, null, 3, 2, null, null);
        acceptCompat(belowMax.getToken(), user(), null);
        assertThat(usedCount(belowMax.getId())).isEqualTo(3);
        Long late = user();
        assertError(business(() -> acceptCompat(belowMax.getToken(), late, null)), RoleErrorCode.ROLE_003, null);
        assertThat(usedCount(belowMax.getId())).isEqualTo(3);

        InviteTokenEntity atMax = token(scope, 0, null, 3, 3, null, null);
        Long atMaxUser = user();
        assertError(business(() -> acceptCompat(atMax.getToken(), atMaxUser, null)), RoleErrorCode.ROLE_003, null);
        assertThat(usedCount(atMax.getId())).isEqualTo(3);

        InviteTokenEntity overMax = token(scope, 0, null, 3, 5, null, null);
        Long overMaxUser = user();
        assertError(business(() -> acceptCompat(overMax.getToken(), overMaxUser, null)), RoleErrorCode.ROLE_003, null);
        assertThat(usedCount(overMax.getId())).as("既存超過は減らさないが増やさない").isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("B3 red: 基数 b=2,max=3 の受入れ1件は claim 1件（不変式 used_count = b + 非RELEASED claims）")
    void baselineInvariantHoldsWithClaims(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 2, null, null);
        accept(token.getToken(), user());
        long nonReleased = claims(token.getId()).stream().filter(c -> !"RELEASED".equals(c.status())).count();
        assertThat(usedCount(token.getId())).isEqualTo(2 + (int) nonReleased);
        assertThat(nonReleased).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // D1: 辞退（特性）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("D1 特性: 辞退は失効＋監査（種別・引数・JSON 同一、非同期）、2回目は監査なし、他人は 403 ROLE_009")
    void decline(String type) throws Exception {
        Scope scope = scope(type);
        Long issuer = admin(scope);
        Long target = user();
        InviteTokenEntity token = inTx(() -> tokens.saveAndFlush(token(scope, 0, null, 1, 0, target, null).toBuilder()
                .createdBy(issuer).build()));

        Long stranger = user();
        BusinessException forbidden = business(() -> declineCompat(token.getToken(), stranger));
        assertError(forbidden, RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN);
        assertThat(revokedAt(token.getId())).isNull();

        declineCompat(token.getToken(), target);
        assertThat(revokedAt(token.getId())).isNotNull();
        String event = "TEAM".equals(type) ? "TEAM_MEMBERSHIP_INVITE_DECLINED" : "ORGANIZATION_MEMBERSHIP_INVITE_DECLINED";
        Map<String, Object> audit = awaitAudit(event, target, 1);
        assertThat(asLong(audit.get("target_user_id"))).isEqualTo(target);
        assertThat(asLong(audit.get("team_id"))).isEqualTo("TEAM".equals(type) ? scope.id() : null);
        assertThat(asLong(audit.get("organization_id"))).isEqualTo("TEAM".equals(type) ? null : scope.id());
        assertThat(String.valueOf(audit.get("metadata")).replace(" ", ""))
                .isEqualTo("{\"reason\":\"DECLINED\",\"token_id\":" + token.getId() + "}");

        LocalDateTime firstRevocation = revokedAt(token.getId());
        declineCompat(token.getToken(), target);
        TimeUnit.MILLISECONDS.sleep(1500);
        assertThat(auditCount(event, target)).as("2回目は監査なし").isEqualTo(1);
        assertThat(revokedAt(token.getId())).as("冪等: 失効日時を上書きしない").isEqualTo(firstRevocation);
    }

    private Map<String, Object> awaitAudit(String event, Long userId, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (auditCount(event, userId) >= expected) {
                return jdbc.queryForMap("SELECT user_id, target_user_id, team_id, organization_id, metadata FROM audit_logs "
                        + "WHERE event_type = ? AND user_id = ? ORDER BY id LIMIT 1", event, userId);
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("監査 " + event + " が10秒以内に記録されない");
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private long auditCount(String event, Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_type = ? AND user_id = ?", Long.class,
                event, userId);
    }

    // ------------------------------------------------------------------
    // F2: 招待付き登録（特性・別戦役 (d) = CMP-261107-1833）
    //   現行は登録直後が PENDING_VERIFICATION のため T1 で ROLE_002 となり登録ごと巻き戻る。
    //   ベータ制限有効時は招待必須なので、招待付き登録が常に失敗する疑い。本 PR は現状を固定するだけで直さない。
    //   資格方針の見直しは別戦役 CMP-261107-1833 で扱う（この試験はその戦役で意図的に書き換える）。
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("F2 特性（別戦役 (d)・陣立て書 §13）: 有効招待付き登録は ROLE_002 で登録ごと巻き戻る")
    void registrationWithInviteRollsBackEntirely(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
        String email = "f2-" + UUID.randomUUID() + "@example.com";
        RegisterRequest req = new RegisterRequest(email, "Passw0rd!Passw0rd", "招待", "登録", "招待登録", "100-0001",
                "ja", "Asia/Tokyo", token.getToken(), "1990-01-01", true, "2026-01-01");

        AtomicBoolean registeredEvent = new AtomicBoolean();
        ApplicationListener<PayloadApplicationEvent<?>> listener = event -> {
            if (event.getPayload() instanceof UserRegisteredEvent registered && email.equals(registered.getEmail())) {
                registeredEvent.set(true);
            }
        };
        ApplicationEventMulticaster multicaster = context.getBean("applicationEventMulticaster", ApplicationEventMulticaster.class);
        multicaster.addApplicationListener(listener);
        try {
            assertThatThrownBy(() -> registration.register(req, "203.0.113." + (int) (Math.random() * 200)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(RoleErrorCode.ROLE_002));
        } finally {
            multicaster.removeApplicationListener(listener);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email))
                .as("users 行は残らない").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens t LEFT JOIN users u ON u.id = t.user_id "
                + "WHERE u.id IS NULL", Long.class)).as("孤児のメール確認トークン行なし").isZero();
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claimCountOrZero(token.getId())).isZero();
        assertThat(registeredEvent).as("UserRegisteredEvent 無し").isFalse();
    }

    // ------------------------------------------------------------------
    // M1: claims DDL（red）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("M1 red: invite_token_claims の列（型・NOT NULL）が揃う。索引・FK・照合順序は FlywayInviteTokenClaimsMigrationTest")
    void claimsTableShape() {
        requireClaimsTable();
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                CLAIMS);
        assertThat(columns).extracting(c -> c.get("COLUMN_NAME"))
                .contains("id", "token_id", "user_id", "status", "created_at", "updated_at");
        Map<String, Object> id = columns.stream().filter(c -> "id".equals(c.get("COLUMN_NAME"))).findFirst().orElseThrow();
        assertThat(id.get("DATA_TYPE")).isEqualTo("binary");
        assertThat(((Number) id.get("CHARACTER_MAXIMUM_LENGTH")).intValue()).isEqualTo(16);
        for (String notNull : List.of("token_id", "user_id", "status")) {
            assertThat(columns.stream().filter(c -> notNull.equals(c.get("COLUMN_NAME"))).findFirst().orElseThrow()
                    .get("IS_NULLABLE")).as(notNull + " は NOT NULL").isEqualTo("NO");
        }

        // 索引・FK・照合順序は IT の Entity 由来スキーマ（ddl-auto=create）には無い。実 migration は
        // FlywayInviteTokenClaimsMigrationTest で検証する（陣立て書 §9.1）。

    }
}
