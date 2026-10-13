package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.event.MfaEnabledEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練: TOTP 有効化確定（{@code POST /api/v1/auth/2fa/verify}）の有効化済みガード。
 *
 * <p>穴: {@code Auth2faService#verifyTotpSetup} に有効化済みガードが無く、有効化済みユーザーが正しい
 * TOTP を送ると再び有効化され、<b>バックアップコードが黙って全て差し替わり</b>（手元の印刷済みコードが無効化）、
 * {@code MfaEnabledEvent} が二重発行されていた。</p>
 */
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 TOTP 有効化確定の有効化済みガード（AC-1）")
class TotpSetupGuardIT extends MfaItSupport {

    private static final String VERIFY_PATH = "/api/v1/auth/2fa/verify";

    @Autowired
    private ApplicationEvents events;

    /**
     * AC-1: 有効化済みで POST /2fa/verify → 409（AUTH_078）。バックアップコードは不変、MfaEnabledEvent は出ない。
     */
    @Test
    @DisplayName("AC-1 有効化済みでverifyすると409・バックアップコード不変・有効化イベント無し")
    void 有効化済みでverifyすると409でバックアップコードは不変() throws Exception {
        MfaUser u = createTotpUser();
        String before = backupCodesJson(u.userId());

        MvcResult result = mockMvc.perform(post(VERIFY_PATH).servletPath(VERIFY_PATH)
                        .with(user(String.valueOf(u.userId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("totpCode", currentTotp(u.totpSecret())))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("AC-1 有効化済みは 409: %s", body(result)).isEqualTo(409);
        assertThat(errorCode(result)).as("AC-1 有効化済みのエラーコード").isEqualTo("AUTH_078");
        assertThat(backupCodesJson(u.userId())).as("AC-1 バックアップコードは差し替わらない").isEqualTo(before);
        assertThat(events.stream(MfaEnabledEvent.class).count()).as("AC-1 MfaEnabledEvent は発行されない").isZero();
    }

    /**
     * AC-1（Codex 検分指摘）: 同一ユーザーの確定 2 本が並列に来ても成功は 1 件、2 本目は AUTH_078。
     * 保存されたバックアップコードは成功側が返したものと一致する（後着側に上書きされない）。
     *
     * <p>確定は 2FA 行を行ロックしてから「有効化済みか」を読む。ロックなしで読むと両方が「未有効」を読んで成功し、
     * 後から保存された方が先に返したバックアップコードを黙って上書きする。2 本は ±1 ウィンドウで共に有効な
     * 別々のコード（現在・次ウィンドウ）を使い、TOTP 使用済み印では止まらないようにしている。</p>
     */
    @Test
    @DisplayName("AC-1 同一ユーザーの確定の並列2件は成功1件・2件目はAUTH_078・返したバックアップコードが保存される")
    void 確定の並列2件は成功1件() throws Exception {
        long userId = createPlainUser();
        String secret = auth2faService.setupTotp(userId).getData().getSecret();
        String now = currentTotp(secret);
        String next = totpAt(secret, Instant.now().plusSeconds(30));

        List<Object> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        runConcurrently(
                () -> auth2faService.verifyTotpSetup(userId, now),
                () -> auth2faService.verifyTotpSetup(userId, next),
                successes, failures);

        assertThat(successes).as("成功は 1 件だけ（失敗: %s）", failures).hasSize(1);
        assertThat(failures).as("2 件目は有効化済み").containsExactly("AUTH_078");
        @SuppressWarnings("unchecked")
        List<String> returned = ((ApiResponse<BackupCodesResponse>) successes.get(0)).getData().getBackupCodes();
        List<String> storedHashes = objectMapper.readValue(backupCodesJson(userId),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(storedHashes).as("保存件数").hasSize(returned.size());
        for (int i = 0; i < returned.size(); i++) {
            assertThat(passwordEncoder.matches(returned.get(i), storedHashes.get(i)))
                    .as("成功側が返したバックアップコード %d が保存されている（上書きされていない）", i).isTrue();
        }
    }

    /** 2 つの処理を同時に開始させ、戻り値と失敗（BusinessException）のコードを集める。 */
    private static void runConcurrently(Callable<?> a, Callable<?> b,
                                        List<Object> successes, List<String> failureCodes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                try {
                    successes.add(f.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    failureCodes.add(cause instanceof BusinessException be
                            ? be.getErrorCode().getCode() : String.valueOf(cause));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
