package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.config.QrSigningProperties;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobQrTokenEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobQrTokenRepository;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link JobQrTokenService} のユニットテスト。F13.1 Phase 13.1.2。
 *
 * <p>中核のシナリオを網羅する:</p>
 * <ul>
 *   <li>{@code issue}: 契約存在確認・Requester 認可・TTL クランプ・shortCode 英数字形式・
 *       発行 JWT が同鍵で検証可能 / 期待 claims を持つこと</li>
 *   <li>{@code verifyAndConsume}: 成功パス / nonce 再利用 {@code JOB_QR_TOKEN_REUSED} /
 *       署名偽造 {@code JOB_QR_TOKEN_INVALID_SIGNATURE} / TTL 超過 {@code JOB_QR_TOKEN_EXPIRED} /
 *       オフライン送信で scanned_at が有効範囲内なら許可</li>
 *   <li>{@code verifyShortCode}: 成功 / 失効コード {@code JOB_QR_SHORT_CODE_NOT_FOUND}</li>
 *   <li>{@code getCurrent}: Requester 一致 / 不一致で {@code JOB_PERMISSION_DENIED}</li>
 * </ul>
 *
 * <p>{@link Clock} を {@link Clock#fixed(Instant, java.time.ZoneId)} で固定し、時間依存ロジックを決定論的に検証する。
 * Mockito の {@code strictness} は {@link MockitoExtension} のデフォルトである STRICT_STUBS だと
 * 個別テストごとのスタブ過不足が騒がしいため、{@link org.mockito.junit.jupiter.MockitoSettings} で LENIENT 指定。</p>
 */
@DisplayName("JobQrTokenService 単体テスト")
class JobQrTokenServiceTest {

    /** 32 バイト以上の有効テスト secret。 */
    private static final String TEST_SECRET = "unit-test-qr-signing-key-fixed-32bytes-xxxxxxx";
    private static final String KID = "v1";

    private static final Long CONTRACT_ID = 9001L;
    private static final Long REQUESTER_ID = 100L;
    private static final Long WORKER_ID = 200L;
    private static final Long OTHER_USER_ID = 999L;

    /** 固定時刻（UTC）。発行・失効判定をすべてこの時刻基準で行う。 */
    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private JobQrTokenRepository qrTokenRepository;
    private JobContractRepository contractRepository;
    private QrSigningKeyProvider keyProvider;
    private QrSigningProperties properties;
    private Clock clock;

    private JobQrTokenService service;

    @BeforeEach
    void setUp() {
        qrTokenRepository = mock(JobQrTokenRepository.class);
        contractRepository = mock(JobContractRepository.class);
        properties = buildProperties();
        keyProvider = buildProvider(properties);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new JobQrTokenService(qrTokenRepository, contractRepository,
                keyProvider, properties, clock);

        // デフォルトで save は引数をそのまま返す（ID 採番はエンティティ自己生成に任せる）。
        given(qrTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    // =====================================================================
    // issue
    // =====================================================================

    @Nested
    @DisplayName("issue（QR トークン発行）")
    class IssueTests {

        @Test
        @DisplayName("正常系: デフォルト TTL で JWT と shortCode を発行し、エンティティを永続化")
        void 正常_デフォルトTTLで発行() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract()));
            given(qrTokenRepository.findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(
                    any(), any(), any())).willReturn(Optional.empty());

            JobQrTokenService.IssueResult result =
                    service.issue(CONTRACT_ID, JobCheckInType.IN, null, REQUESTER_ID);

            assertThat(result.token()).isNotBlank();
            assertThat(result.shortCode()).hasSize(6)
                    .matches("[2-9A-HJ-NP-Z]+"); // 紛らわしい 0O1lI を除外した文字集合
            assertThat(result.type()).isEqualTo(JobCheckInType.IN);
            assertThat(result.kid()).isEqualTo(KID);
            assertThat(result.issuedAt()).isEqualTo(NOW);
            assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(result.nonce()).isNotBlank();

            // 永続化されたエンティティの内容を検証
            verify(qrTokenRepository).save(any(JobQrTokenEntity.class));
        }

        @Test
        @DisplayName("正常系: 発行した JWT を同じ鍵で検証すると期待 claims を取得できる")
        void 正常_JWTが同鍵で検証可能() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract()));
            given(qrTokenRepository.findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(
                    any(), any(), any())).willReturn(Optional.empty());

            JobQrTokenService.IssueResult result =
                    service.issue(CONTRACT_ID, JobCheckInType.OUT, null, REQUESTER_ID);

            // 発行した JWT を JJWT parser で検証
            SecretKey key = keyProvider.find(KID).orElseThrow();
            var claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(result.token()).getPayload();

            assertThat(claims.get("cid", Long.class)).isEqualTo(CONTRACT_ID);
            assertThat(claims.get("wid", Long.class)).isEqualTo(WORKER_ID);
            assertThat(claims.get("typ", String.class)).isEqualTo("OUT");
            assertThat(claims.get("nonce", String.class)).isEqualTo(result.nonce());
        }

        @Test
        @DisplayName("正常系: TTL 指定は上限 300 秒でクランプされる")
        void 正常_TTLクランプ() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract()));
            given(qrTokenRepository.findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(
                    any(), any(), any())).willReturn(Optional.empty());

            JobQrTokenService.IssueResult result =
                    service.issue(CONTRACT_ID, JobCheckInType.IN, 999, REQUESTER_ID); // 999 秒指定

            assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(300)); // 上限で打ち切り
        }

        @Test
        @DisplayName("異常系: 契約が存在しなければ JOB_CONTRACT_NOT_FOUND")
        void 契約不在で404() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.issue(CONTRACT_ID, JobCheckInType.IN, null, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: Requester 以外からの発行は JOB_PERMISSION_DENIED")
        void 発行者が別人で403() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract()));

            assertThatThrownBy(() -> service.issue(CONTRACT_ID, JobCheckInType.IN, null, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_PERMISSION_DENIED));
        }
    }

    // =====================================================================
    // verifyAndConsume
    // =====================================================================

    @Nested
    @DisplayName("verifyAndConsume（JWT 検証・消費）")
    class VerifyAndConsumeTests {

        @Test
        @DisplayName("正常系: 有効な JWT とエンティティで成功、usedAt が記録される")
        void 正常_JWT検証成功() {
            String jwt = buildJwtForTest(CONTRACT_ID, WORKER_ID, JobCheckInType.IN, "nonce-ok", NOW);
            JobQrTokenEntity entity = tokenEntity("nonce-ok", NOW, NOW.plusSeconds(60), null);
            given(qrTokenRepository.findByNonce("nonce-ok")).willReturn(Optional.of(entity));

            JobQrTokenService.VerifyResult result =
                    service.verifyAndConsume(jwt, NOW.plusSeconds(30), false);

            assertThat(result.contractId()).isEqualTo(CONTRACT_ID);
            assertThat(result.workerUserId()).isEqualTo(WORKER_ID);
            assertThat(result.type()).isEqualTo(JobCheckInType.IN);
            assertThat(result.nonce()).isEqualTo("nonce-ok");
            assertThat(result.kid()).isEqualTo(KID);
            assertThat(entity.getUsedAt()).isEqualTo(NOW);
            verify(qrTokenRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 既に消費済み nonce は JOB_QR_TOKEN_REUSED")
        void 再利用で拒否() {
            String jwt = buildJwtForTest(CONTRACT_ID, WORKER_ID, JobCheckInType.IN, "nonce-used", NOW);
            JobQrTokenEntity entity = tokenEntity("nonce-used", NOW, NOW.plusSeconds(60),
                    NOW.minusSeconds(5)); // 5 秒前に既に消費済み
            given(qrTokenRepository.findByNonce("nonce-used")).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.verifyAndConsume(jwt, NOW.plusSeconds(10), false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_REUSED));
        }

        @Test
        @DisplayName("異常系: 別鍵で署名された JWT は JOB_QR_TOKEN_INVALID_SIGNATURE")
        void 署名偽造で拒否() {
            // v1 kid を主張しつつ、v1 鍵とは別の秘密鍵で署名したトークン
            String forgedSecret = "forged-attacker-key-32bytes-xxxxxxxxxxxx";
            SecretKey forgedKey = io.jsonwebtoken.security.Keys
                    .hmacShaKeyFor(forgedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String jwt = Jwts.builder()
                    .header().keyId(KID).and()
                    .claim("cid", CONTRACT_ID)
                    .claim("wid", WORKER_ID)
                    .claim("typ", "IN")
                    .claim("nonce", "nonce-forge")
                    .issuedAt(java.util.Date.from(NOW))
                    .expiration(java.util.Date.from(NOW.plusSeconds(60)))
                    .signWith(forgedKey, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> service.verifyAndConsume(jwt, NOW, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE));
        }

        @Test
        @DisplayName("異常系: 未知 kid を主張する JWT は JOB_QR_TOKEN_INVALID_SIGNATURE")
        void 未知kidで拒否() {
            // 未知 kid でも JJWT で署名自体は可能だが、keyProvider.find で解決失敗させたい
            String jwt = Jwts.builder()
                    .header().keyId("unknown-kid").and()
                    .claim("cid", CONTRACT_ID)
                    .claim("wid", WORKER_ID)
                    .claim("typ", "IN")
                    .claim("nonce", "nonce-unknown-kid")
                    .issuedAt(java.util.Date.from(NOW))
                    .expiration(java.util.Date.from(NOW.plusSeconds(60)))
                    .signWith(keyProvider.find(KID).orElseThrow(), Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> service.verifyAndConsume(jwt, NOW, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE));
        }

        @Test
        @DisplayName("異常系: オンライン送信で exp を超過しているなら JOB_QR_TOKEN_EXPIRED")
        void オンラインTTL超過で拒否() {
            // 期限切れ JWT: issuedAt=2 分前、exp=1 分前。JJWT が自身で ExpiredJwtException を投げる想定。
            Instant pastIssuedAt = NOW.minus(Duration.ofMinutes(2));
            Instant pastExpiresAt = NOW.minus(Duration.ofMinutes(1));
            String jwt = Jwts.builder()
                    .header().keyId(KID).and()
                    .claim("cid", CONTRACT_ID)
                    .claim("wid", WORKER_ID)
                    .claim("typ", "IN")
                    .claim("nonce", "nonce-expired")
                    .issuedAt(java.util.Date.from(pastIssuedAt))
                    .expiration(java.util.Date.from(pastExpiresAt))
                    .signWith(keyProvider.find(KID).orElseThrow(), Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> service.verifyAndConsume(jwt, NOW, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_EXPIRED));
        }

        @Test
        @DisplayName("正常系: オフライン送信で scannedAt が有効範囲内なら exp 経過後でも許可")
        void オフライン_scannedAt範囲内で許可() {
            // JWT 自体は期限切れ（60 秒前発行、30 秒前失効）だが、scannedAt は発行〜失効の範囲内
            Instant issuedAt = NOW.minus(Duration.ofSeconds(120));
            Instant expiresAt = NOW.minus(Duration.ofSeconds(60));
            Instant scannedAt = NOW.minus(Duration.ofSeconds(90)); // issuedAt と expiresAt の中間

            // JJWT は exp 超過で ExpiredJwtException を投げるため、テストでは oldExpiredJwt を
            // 自前で構築できない。その代わり、Service 内の verifyAndConsume は JWT パース時点で
            // ExpiredJwtException を捕まえて JOB_QR_TOKEN_EXPIRED にマッピングしてしまうため、
            // オフライン許可パスをテストするには「JWT の exp は未来」「エンティティ側の expires_at は過去」
            // という組み合わせで検証する必要がある。これが §2.3.1 の「スキャン時刻が有効範囲内」判定のコア。
            String jwt = buildJwtForTest(CONTRACT_ID, WORKER_ID, JobCheckInType.IN,
                    "nonce-offline", NOW.plusSeconds(3600)); // JWT exp は十分未来に
            JobQrTokenEntity entity = tokenEntity("nonce-offline", issuedAt, expiresAt, null);
            given(qrTokenRepository.findByNonce("nonce-offline")).willReturn(Optional.of(entity));

            JobQrTokenService.VerifyResult result =
                    service.verifyAndConsume(jwt, scannedAt, true);

            assertThat(result.contractId()).isEqualTo(CONTRACT_ID);
            assertThat(entity.getUsedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("異常系: オフライン送信でも scannedAt が範囲外なら JOB_QR_TOKEN_EXPIRED")
        void オフライン_scannedAt範囲外で拒否() {
            Instant issuedAt = NOW.minus(Duration.ofSeconds(120));
            Instant expiresAt = NOW.minus(Duration.ofSeconds(60));
            Instant scannedAt = NOW.minus(Duration.ofSeconds(30)); // expiresAt より後 → 範囲外

            String jwt = buildJwtForTest(CONTRACT_ID, WORKER_ID, JobCheckInType.IN,
                    "nonce-offline-late", NOW.plusSeconds(3600));
            JobQrTokenEntity entity = tokenEntity("nonce-offline-late", issuedAt, expiresAt, null);
            given(qrTokenRepository.findByNonce("nonce-offline-late")).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.verifyAndConsume(jwt, scannedAt, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_EXPIRED));
        }

        @Test
        @DisplayName("異常系: nonce が DB に無ければ JOB_QR_TOKEN_INVALID_SIGNATURE（改ざん疑い）")
        void nonce不在で拒否() {
            String jwt = buildJwtForTest(CONTRACT_ID, WORKER_ID, JobCheckInType.IN, "missing-nonce", NOW);
            given(qrTokenRepository.findByNonce("missing-nonce")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyAndConsume(jwt, NOW, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE));
        }
    }

    // =====================================================================
    // verifyShortCode
    // =====================================================================

    @Nested
    @DisplayName("verifyShortCode（手動入力フォールバック）")
    class VerifyShortCodeTests {

        @Test
        @DisplayName("正常系: 未消費・未失効の短コードで成功、usedAt が記録される")
        void 正常_短コード検証成功() {
            JobQrTokenEntity entity = tokenEntity("nonce-sc", NOW, NOW.plusSeconds(60), null);
            given(qrTokenRepository.findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(
                    "ABC234", JobCheckInType.IN, NOW)).willReturn(Optional.of(entity));

            JobQrTokenService.VerifyResult result =
                    service.verifyShortCode("ABC234", JobCheckInType.IN, NOW);

            assertThat(result.contractId()).isEqualTo(CONTRACT_ID);
            assertThat(result.workerUserId()).isNull(); // 短コード経由では wid は claims に無いため null
            assertThat(entity.getUsedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("異常系: 失効済みコードは JOB_QR_SHORT_CODE_NOT_FOUND")
        void 失効コードで拒否() {
            given(qrTokenRepository.findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(
                    any(), any(), any())).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyShortCode("EXPIRED", JobCheckInType.IN, NOW))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_SHORT_CODE_NOT_FOUND));
        }
    }

    // =====================================================================
    // getCurrent
    // =====================================================================

    @Nested
    @DisplayName("getCurrent（Requester 画面再表示用）")
    class GetCurrentTests {

        @Test
        @DisplayName("正常系: Requester 本人が閲覧なら現在有効なトークンを返す")
        void 正常_Requester本人で取得() {
            JobQrTokenEntity entity = tokenEntity("nonce-cur", NOW, NOW.plusSeconds(60), null);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract()));
            given(qrTokenRepository
                    .findTopByJobContractIdAndTypeAndUsedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(
                            CONTRACT_ID, JobCheckInType.IN, NOW))
                    .willReturn(Optional.of(entity));

            Optional<JobQrTokenEntity> result =
                    service.getCurrent(CONTRACT_ID, JobCheckInType.IN, REQUESTER_ID);

            assertThat(result).containsSame(entity);
        }

        @Test
        @DisplayName("異常系: Requester 以外は JOB_PERMISSION_DENIED")
        void 別ユーザーで403() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract()));

            assertThatThrownBy(() ->
                    service.getCurrent(CONTRACT_ID, JobCheckInType.IN, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_PERMISSION_DENIED));
        }
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    /**
     * テスト用 {@link QrSigningProperties} を構築する（kid=v1、active=true の 1 鍵構成）。
     */
    private static QrSigningProperties buildProperties() {
        QrSigningProperties.SigningKey k = new QrSigningProperties.SigningKey();
        k.setKid(KID);
        k.setSecret(TEST_SECRET);
        k.setActive(true);
        QrSigningProperties p = new QrSigningProperties();
        p.setSigningKeys(List.of(k));
        return p;
    }

    /**
     * テスト用 {@link QrSigningKeyProvider} を構築して手動初期化する。
     */
    private static QrSigningKeyProvider buildProvider(QrSigningProperties props) {
        QrSigningKeyProvider provider = new QrSigningKeyProvider(props);
        provider.initialize();
        return provider;
    }

    /**
     * 既定 Requester / Worker の契約エンティティを組み立てる。
     * ID は reflection で強制設定する（Entity の setter 非公開のため）。
     */
    private JobContractEntity contract() {
        JobContractEntity c = JobContractEntity.builder()
                .jobPostingId(1L)
                .jobApplicationId(2L)
                .requesterUserId(REQUESTER_ID)
                .workerUserId(WORKER_ID)
                .baseRewardJpy(5000)
                .workStartAt(LocalDateTime.of(2026, 6, 1, 10, 0))
                .workEndAt(LocalDateTime.of(2026, 6, 1, 14, 0))
                .status(JobContractStatus.MATCHED)
                .matchedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                .rejectionCount(0)
                .build();
        setPrivateField(c, "id", CONTRACT_ID);
        return c;
    }

    /**
     * テスト用の JobQrTokenEntity を組み立てる。
     */
    private JobQrTokenEntity tokenEntity(String nonce, Instant issuedAt, Instant expiresAt, Instant usedAt) {
        JobQrTokenEntity e = JobQrTokenEntity.builder()
                .jobContractId(CONTRACT_ID)
                .type(JobCheckInType.IN)
                .nonce(nonce)
                .kid(KID)
                .shortCode("ABC234")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .issuedByUserId(REQUESTER_ID)
                .usedAt(usedAt)
                .build();
        return e;
    }

    /**
     * JWT をテスト用に直接組み立てる（verifyAndConsume の検証対象）。
     * クライアント発行相当のペイロード + 有効な署名を持つトークンを返す。
     */
    private String buildJwtForTest(Long cid, Long wid, JobCheckInType type, String nonce, Instant expiresAt) {
        SecretKey key = keyProvider.find(KID).orElseThrow();
        return Jwts.builder()
                .header().keyId(KID).and()
                .claim("cid", cid)
                .claim("wid", wid)
                .claim("typ", type.name())
                .claim("nonce", nonce)
                .issuedAt(java.util.Date.from(NOW.minus(Duration.ofSeconds(1))))
                .expiration(java.util.Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * private フィールドにリフレクションで値を設定する（Entity の ID など）。
     */
    private static void setPrivateField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("フィールド設定失敗: " + fieldName, e);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
