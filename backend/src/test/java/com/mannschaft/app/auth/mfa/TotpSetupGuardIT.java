package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.event.MfaEnabledEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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

    @Autowired
    private ApplicationContext applicationContext;

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
     * AC-1（Codex 検分指摘）: 同一ユーザーの確定が並列に来ても成功は 1 件、後着は AUTH_078。
     * 保存されたバックアップコードは成功側が返したものと一致する（後着側に上書きされない）。
     *
     * <p>競合の順序を固定する: 先行の確定が有効化してイベントを出した瞬間（commit 前）に、後続の確定を別スレッド
     * （別 TX）で開始し、後続が先行のロックを待つのを MySQL で観測してから先行を commit させる（{@link ConcurrentOnEvent}）。2 本は ±1 ウィンドウで共に有効な
     * 別々のコード（現在・次ウィンドウ）を使い、TOTP 使用済み印では止まらないようにしている。</p>
     * <ul>
     *   <li>行をロックせずに「有効化済みか」を読む実装: 後続は先行の未 commit の有効化を見ずに「未有効」と読んで
     *       進み、先行の commit 後に自分のバックアップコードで上書きして成功する（成功 2 件）。</li>
     *   <li>行ロックで読む実装: 後続は先行の commit まで待たされ、commit 後の「有効化済み」を読んで AUTH_078。</li>
     * </ul>
     */
    @Test
    @DisplayName("AC-1 確定が先行のcommit待ちで並んでも成功1件・後着はAUTH_078・返したバックアップコードが保存される")
    void 確定の並列2件は成功1件() throws Exception {
        long userId = createPlainUser();
        String secret = auth2faService.setupTotp(userId).getData().getSecret();
        String now = currentTotp(secret);
        String next = totpAt(secret, Instant.now().plusSeconds(30));

        ApiResponse<BackupCodesResponse> leader;
        Throwable followerFailure;
        try (ConcurrentOnEvent race = ConcurrentOnEvent.arm(applicationContext, MfaEnabledEvent.class,
                () -> auth2faService.verifyTotpSetup(userId, next), MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            leader = auth2faService.verifyTotpSetup(userId, now);

            assertThat(race.fired()).as("前提: 先行の commit 前に後続を開始した").isTrue();
            assertThat(race.observedLockWait())
                    .as("前提: 先行の commit 前に、後続が先行のロックを待つのを MySQL で観測した（%s）", race.observation())
                    .isTrue();
            followerFailure = catchThrowable(() -> race.follower().get(60, TimeUnit.SECONDS));
        }

        assertThat(followerFailure).as("後着は失敗する").isInstanceOf(ExecutionException.class);
        assertThat(followerFailure.getCause()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) followerFailure.getCause()).getErrorCode().getCode())
                .as("後着は有効化済み").isEqualTo("AUTH_078");
        List<String> returned = leader.getData().getBackupCodes();
        List<String> storedHashes = objectMapper.readValue(backupCodesJson(userId),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(storedHashes).as("保存件数").hasSize(returned.size());
        for (int i = 0; i < returned.size(); i++) {
            assertThat(passwordEncoder.matches(returned.get(i), storedHashes.get(i)))
                    .as("成功側が返したバックアップコード %d が保存されている（上書きされていない）", i).isTrue();
        }
    }
}
