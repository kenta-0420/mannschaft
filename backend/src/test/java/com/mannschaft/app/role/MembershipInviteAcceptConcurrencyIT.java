package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練（CMP-260826-1457 W1 P1・AC-12d）: 同じ招待への並行操作の整合とデッドロックの不在。
 *
 * <p>同時開始は {@link CountDownLatch} で揃える。各スレッドは自分のスレッドの SecurityContext に利用者を置いてから
 * MockMvc を呼ぶ（MockMvc は呼び出しスレッドで同期実行する）。ロック順はトークン入口・ID 入口とも
 * user 行 → 招待行（陣立て書 §2-2 の 9.）。どの組でも 5xx（デッドロック・ロック待ち超過）を出さず、30 秒以内に終わる。</p>
 *
 * <p>red の理由: いまの main には ID 入口（{@code POST /api/v1/invite/invitations/{id}/accept}）が無い。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W1 P1 AC-12d: 承諾型招待の並行承諾・取消")
class MembershipInviteAcceptConcurrencyIT extends InviteTokenHashItSupport {

    private static final long TIMEOUT_SECONDS = 30;

    private RequestBuilder acceptById(long tokenId) {
        return post("/api/v1/invite/invitations/{id}/accept", tokenId);
    }

    /** 2 つの要求を同時に開始し、ステータスとエラーコードを返す。 */
    private List<Outcome> race(Long actorA, RequestBuilder requestA, Long actorB, RequestBuilder requestB)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = executor.submit(timed(ready, start, actorA, requestA));
            Future<Outcome> b = executor.submit(timed(ready, start, actorB, requestB));
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Outcome> timed(CountDownLatch ready, CountDownLatch start, Long actor, RequestBuilder request) {
        return () -> {
            ready.countDown();
            assertThat(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            try {
                return outcome(perform(actor, request));
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        };
    }

    private long issueToTarget(Scope scope, Long admin, Long target, Long channelId) throws Exception {
        JsonNode data = issueMembershipInvite(channelId, scope, admin);
        return data.path("tokenId").asLong();
    }

    @Test
    @DisplayName("同じ招待への ID 二重承諾は 1 件だけ成功し、参加と利用回数は 1 件ぶん")
    void ID二重承諾は1件だけ成功する() throws Exception {
        Scope scope = scope("TEAM");
        Long admin = issuer(scope);
        Long target = user();
        long tokenId = issueToTarget(scope, admin, target, createDm(admin, target));

        List<Outcome> outcomes = race(target, acceptById(tokenId), target, acceptById(tokenId));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isLessThan(500));
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == 200).hasSize(1);
        assertThat(usedCount(tokenId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND team_id = ?",
                Long.class, target, scope.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("トークン入口と ID 入口が同じ招待へ同時に来ても、1 件だけ成功しデッドロックしない")
    void トークン入口とID入口の混在でも1件だけ成功する() throws Exception {
        Scope scope = scope("TEAM");
        Long target = user();
        String plaintext = vault.issueBase64Url().plaintext();
        Long tokenId = insertTargetedInviteWithKnownPlaintext(scope, plaintext, target, admin(scope),
                LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(7), 1, 0, null);

        List<Outcome> outcomes = race(target, post("/api/v1/invite/{token}/join", plaintext),
                target, acceptById(tokenId));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isLessThan(500));
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == 200).hasSize(1);
        assertThat(usedCount(tokenId)).isEqualTo(1);
    }

    @Test
    @DisplayName("取消の後の ID 承諾は成功しない（404 ROLE_002）")
    void 取消後の承諾は成功しない() throws Exception {
        Scope scope = scope("TEAM");
        Long admin = issuer(scope);
        Long target = user();
        Long channelId = createDm(admin, target);
        long tokenId = issueToTarget(scope, admin, target, channelId);

        assertThat(perform(admin, delete("/api/v1/chat/channels/{cid}/membership-invite/{tokenId}", channelId, tokenId))
                .getStatus()).isLessThan(300);
        assertThat(outcome(perform(target, acceptById(tokenId)))).isEqualTo(new Outcome(404, "ROLE_002"));
        assertThat(isMember(scope, target)).isFalse();
        assertThat(usedCount(tokenId)).isZero();
    }

    @Test
    @DisplayName("承諾と取消が同時に来ても 5xx にならず、承諾が成功したときだけ参加と利用回数 1 が残る")
    void 承諾と取消の競合は整合する() throws Exception {
        Scope scope = scope("TEAM");
        Long admin = issuer(scope);
        Long target = user();
        Long channelId = createDm(admin, target);
        long tokenId = issueToTarget(scope, admin, target, channelId);

        List<Outcome> outcomes = race(target, acceptById(tokenId),
                admin, delete("/api/v1/chat/channels/{cid}/membership-invite/{tokenId}", channelId, tokenId));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isLessThan(500));
        Outcome acceptOutcome = outcomes.get(0);
        // 承諾は「成功」か「取消済みとして不在応答」のどちらかだけ
        assertThat(acceptOutcome.status() == 200 || acceptOutcome.equals(new Outcome(404, "ROLE_002")))
                .as("承諾の応答: %s", acceptOutcome).isTrue();
        boolean accepted = acceptOutcome.status() == 200;
        assertThat(isMember(scope, target)).isEqualTo(accepted);
        assertThat(usedCount(tokenId)).isEqualTo(accepted ? 1 : 0);
        if (!accepted) {
            // 承諾が負けたなら、取消が先に確定している
            assertThat(revokedAt(tokenId)).isNotNull();
        }
    }
}
