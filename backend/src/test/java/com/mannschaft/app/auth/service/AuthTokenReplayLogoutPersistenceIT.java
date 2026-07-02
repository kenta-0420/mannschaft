package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真リプレイ攻撃検出時の「全デバイス無効化」がトランザクションのロールバックで巻き戻らず、
 * <b>実 DB に永続化される</b>ことを守る番人 結合テスト。
 *
 * <h2>このテストが守るバグ（実機 E2E で発見・確定済み）</h2>
 * <p>{@link AuthTokenRotationService#refreshAccessToken}（{@code @Transactional} = REQUIRED）の真リプレイ検出経路は</p>
 * <pre>{@code
 *   authSessionService.logoutAllDevices(existingToken.getUserId()); // 全トークン revoke（JPA ダーティ）
 *   throw new BusinessException(AuthErrorCode.AUTH_026);             // RuntimeException → tx ロールバック
 * }</pre>
 * <p>を実行する。{@code logoutAllDevices} が呼び出し元と同一トランザクション（REQUIRED）で動いていると、
 * 直後の throw による<b>ロールバックで全トークン revoke が巻き戻り</b>、盗難トークン検出時の全デバイス無効化が
 * 実際には永続化されない（＝過小無効化・防御の無力化）。実機では「AUTH_026/401 は返るが、その後も生存トークンで
 * refresh が 200 通ってしまう」ことが確認されている。</p>
 *
 * <h2>根治</h2>
 * <p>{@link AuthSessionService#logoutAllDevices(Long)} を {@code @Transactional(REQUIRES_NEW)} とし、
 * セッション無効化を独立トランザクションで即コミットする。これにより呼び出し元のロールバックでは巻き戻らない。</p>
 *
 * <h2>なぜ純 Mockito UT では守れないのか（false-green）</h2>
 * <p>既存の {@code AuthTokenRotationServiceTest#ac3_...} は {@code logoutAllDevices} を mock で
 * {@code verify} するだけで、トランザクション境界（ロールバック）を一切踏まないため、REQUIRES_NEW を
 * 外して同一トランザクション化しても緑のまま（CI 不可視の回帰）。本結合テストは実 MySQL（Testcontainers）＋
 * 実トランザクション境界を踏むため、この巻き戻りを検知できる。</p>
 *
 * <h2>トートロジー回避</h2>
 * <p>サービス呼び出し回数ではなく、AUTH_026 送出<b>後</b>に実 DB を（JPA 一次キャッシュを介さず JDBC で）読み、
 * 当該ユーザーの全 refresh_token の {@code revoked_at} が確定的に NOT NULL であることをアサートする。</p>
 *
 * <p>Docker（Testcontainers MySQL）が利用可能な環境でのみ実行される。</p>
 */
@DisplayName("真リプレイ検知時 全デバイス無効化の永続化 結合テスト")
// JUnit 5 の @EnabledIf は @Inherited ではないため、派生クラスでも明示的に再宣言する必要がある
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AuthTokenReplayLogoutPersistenceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private AuthTokenRotationService authTokenRotationService;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 真リプレイ（後継有り・grace 超過のリボーク済みトークン再提示）を検出したとき、
     * AUTH_026 送出後も当該ユーザーの全 refresh_token が実 DB 上で revoked_at NOT NULL になっている。
     *
     * <p>本テストメソッドには {@code @Transactional} を付けない（付けるとテストの TX が全体を包み、
     * refreshAccessToken のロールバック挙動を正しく観測できないため）。セットアップの保存行は
     * Testcontainers の JVM ライフサイクル終了でコンテナごと破棄される。ユーザー ID は UUID 由来で一意化し、
     * 他テストの refresh_token とは独立させる。</p>
     */
    @Test
    @DisplayName("真リプレイ検知時_全トークン無効化がDBに永続化される（tx ロールバックで巻き戻らない）")
    void 真リプレイ検知時_全トークン無効化がDBに永続化される() {
        // ── Given: あるユーザーに「真リプレイ元の失効済みトークン A」と「生存中の別デバイス トークン B」を実コミット ──
        // userId は他テストと衝突しないよう UUID 下位ビットから一意採番する（refresh_tokens にクロスドメイン FK は無い）。
        long userId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 5_000_000L;

        // トークン A: ローテーションで正規に置換され（後継有り）、grace を大幅超過して revoke されたトークン = 真リプレイ元。
        String rawReplayedToken = "replayed-raw-" + userId;
        String replayedHash = authTokenService.hashToken(rawReplayedToken);
        RefreshTokenEntity replayed = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(replayedHash)
                .jti(UUID.randomUUID().toString())
                .rememberMe(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .revokedAt(LocalDateTime.now().minusMinutes(10)) // grace（既定 60 秒）を大幅超過
                .replacedByTokenHash(authTokenService.hashToken("successor-of-" + userId)) // 後継有り
                .build();
        refreshTokenRepository.save(replayed);

        // トークン B: 別デバイスの「生存中」トークン。真リプレイ検出時にこれも無効化されねばならない対象。
        RefreshTokenEntity liveOtherDevice = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(authTokenService.hashToken("live-other-device-" + userId))
                .jti(UUID.randomUUID().toString())
                .rememberMe(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        refreshTokenRepository.save(liveOtherDevice);

        // 前提: 無効化前は生存トークンが 1 件存在する
        Integer activeBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        assertThat(activeBefore).as("無効化前の生存トークン数").isEqualTo(1);

        // ── When: トークン A を再提示 → 真リプレイ検出 → AUTH_026（RuntimeException で tx ロールバック） ──
        assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawReplayedToken, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                        .isEqualTo("AUTH_026"));

        // ── Then: AUTH_026 送出後も、当該ユーザーの全トークンが実 DB 上で revoked_at NOT NULL である ──
        // JPA 一次キャッシュを介さず JDBC で実 DB の確定状態を読む（決定的証拠）。
        Integer activeAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        assertThat(activeAfter)
                .as("真リプレイ検出後は生存トークンが 0 でなければならない（REQUIRES_NEW でないと revoke が巻き戻り 1 のまま残る）")
                .isEqualTo(0);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        assertThat(total).as("当該ユーザーのトークン総数（A + B）").isEqualTo(2);
    }
}
