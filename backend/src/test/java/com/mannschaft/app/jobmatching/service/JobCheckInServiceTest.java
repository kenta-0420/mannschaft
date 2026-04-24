package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.config.QrSigningProperties;
import com.mannschaft.app.jobmatching.entity.JobCheckInEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.entity.JobQrTokenEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobCheckInRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.command.CheckInCommand;
import com.mannschaft.app.jobmatching.service.command.CheckInResult;
import com.mannschaft.app.jobmatching.state.JobContractStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link JobCheckInService} のユニットテスト。F13.1 Phase 13.1.2（足軽参）。
 *
 * <p>以下のシナリオを網羅する:</p>
 * <ul>
 *   <li>IN 成立 — MATCHED→CHECKED_IN→IN_PROGRESS の二段遷移、scannedAt 記録、通知発火</li>
 *   <li>OUT 成立 — IN_PROGRESS→CHECKED_OUT、workDurationMinutes 計算</li>
 *   <li>既存 IN に対する再送 — {@code JOB_CHECK_IN_ALREADY_EXISTS}</li>
 *   <li>IN 未登録で OUT — {@code JOB_CHECK_OUT_BEFORE_CHECK_IN}</li>
 *   <li>掛け持ち検出 — {@code JOB_CHECK_IN_CONCURRENT_CONFLICT}</li>
 *   <li>Worker 本人不一致 — {@code JOB_QR_TOKEN_WRONG_WORKER}</li>
 *   <li>Geolocation 500m 超 — {@code geo_anomaly=TRUE}（チェックインは成立）</li>
 *   <li>Geolocation accuracy &gt; 100m — 判定スキップ</li>
 * </ul>
 *
 * <p>Haversine 計算は業務場所の緯度経度が {@link JobPostingEntity} 側で未保持のため、
 * {@link GeolocationService#isAnomaly} のスタブ（mock）経由で決定論的にブール値のみ制御する
 * （距離計算自体は {@link GeolocationServiceTest} で検証済み）。</p>
 */
@DisplayName("JobCheckInService 単体テスト")
class JobCheckInServiceTest {

    private static final Long CONTRACT_ID = 9001L;
    private static final Long POSTING_ID = 5001L;
    private static final Long REQUESTER_ID = 100L;
    private static final Long WORKER_ID = 200L;
    private static final Long OTHER_WORKER_ID = 300L;
    private static final Long TOKEN_ID = 7001L;
    private static final Long CHECKIN_ID = 8001L;

    /** 固定テスト時刻（UTC）。 */
    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private JobContractRepository contractRepository;
    private JobCheckInRepository checkInRepository;
    private JobPostingRepository postingRepository;
    private JobQrTokenService qrTokenService;
    private GeolocationService geolocationService;
    private JobContractStateMachine stateMachine;
    private JobPolicy jobPolicy;
    private JobNotificationService notificationService;
    private QrSigningProperties qrProperties;
    private Clock clock;

    private JobCheckInService service;

    @BeforeEach
    void setUp() {
        contractRepository = mock(JobContractRepository.class);
        checkInRepository = mock(JobCheckInRepository.class);
        postingRepository = mock(JobPostingRepository.class);
        qrTokenService = mock(JobQrTokenService.class);
        geolocationService = mock(GeolocationService.class);
        stateMachine = mock(JobContractStateMachine.class);
        jobPolicy = mock(JobPolicy.class);
        notificationService = mock(JobNotificationService.class);
        qrProperties = new QrSigningProperties();
        qrProperties.setAnomalyDistanceMeters(500);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new JobCheckInService(
                contractRepository, checkInRepository, postingRepository,
                qrTokenService, geolocationService, stateMachine, jobPolicy,
                notificationService, qrProperties, clock);

        // save は引数を ID 付きで返却（CheckIn の ID 埋め）。
        given(checkInRepository.save(any(JobCheckInEntity.class))).willAnswer(inv -> {
            JobCheckInEntity e = inv.getArgument(0);
            setId(e, CHECKIN_ID);
            return e;
        });
        given(contractRepository.save(any(JobContractEntity.class))).willAnswer(inv -> inv.getArgument(0));
        // geolocation 判定のデフォルトは非 anomaly。個別テストで上書きする。
        given(geolocationService.isAnomaly(any(), any(), anyDouble())).willReturn(false);
        given(geolocationService.distanceMeters(any(), any(), any(), any())).willReturn(Optional.empty());
    }

    // =====================================================================
    // IN 成立
    // =====================================================================

    @Nested
    @DisplayName("recordCheckIn（IN 成立）")
    class RecordCheckInInTests {

        @Test
        @DisplayName("正常系: MATCHED → CHECKED_IN → IN_PROGRESS の二段遷移、scannedAt 記録、通知発火")
        void 正常_IN成立_二段遷移() {
            JobContractEntity contract = contractWith(JobContractStatus.MATCHED);
            JobPostingEntity posting = postingOpen();
            stubFindWithPolicy(contract, posting, JobCheckInType.IN, true);
            stubTokenVerify(contract, JobCheckInType.IN);

            CheckInCommand cmd = cmdIn("valid-jwt");

            CheckInResult result = service.recordCheckIn(cmd);

            // 契約ステータス変化の検証。
            assertThat(contract.getStatus()).isEqualTo(JobContractStatus.IN_PROGRESS);
            assertThat(contract.getCheckedInAt()).isEqualTo(cmd.scannedAt());
            assertThat(result.newStatus()).isEqualTo(JobContractStatus.IN_PROGRESS);
            assertThat(result.type()).isEqualTo(JobCheckInType.IN);
            assertThat(result.geoAnomaly()).isFalse();
            assertThat(result.checkInId()).isEqualTo(CHECKIN_ID);

            // 二段の StateMachine 検証呼び出し。
            verify(stateMachine).validate(JobContractStatus.MATCHED, JobContractStatus.CHECKED_IN);
            verify(stateMachine).validate(JobContractStatus.CHECKED_IN, JobContractStatus.IN_PROGRESS);

            // JWT 検証の呼び出し。
            verify(qrTokenService).verifyAndConsume(eq("valid-jwt"), eq(cmd.scannedAt()), eq(false));

            // 通知発火（IN のみ）。
            verify(notificationService).notifyCheckedIn(CONTRACT_ID);
            verify(notificationService, never()).notifyCheckedOut(any());
            verify(notificationService, never()).notifyGeoAnomaly(any(), anyDouble());
        }

        @Test
        @DisplayName("境界: 認可失敗（Worker 不一致）は JOB_QR_TOKEN_WRONG_WORKER")
        void 認可失敗_Worker不一致() {
            JobContractEntity contract = contractWith(JobContractStatus.MATCHED);
            given(contractRepository.findByIdForUpdate(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(jobPolicy.canRecordCheckIn(contract, OTHER_WORKER_ID, JobCheckInType.IN)).willReturn(false);

            CheckInCommand cmd = cmdInAs(OTHER_WORKER_ID, "valid-jwt");

            assertThatThrownBy(() -> service.recordCheckIn(cmd))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_WRONG_WORKER));
        }

        @Test
        @DisplayName("境界: 既に IN レコードあり → JOB_CHECK_IN_ALREADY_EXISTS")
        void 重複_ALREADY_EXISTS() {
            JobContractEntity contract = contractWith(JobContractStatus.MATCHED);
            given(contractRepository.findByIdForUpdate(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(jobPolicy.canRecordCheckIn(contract, WORKER_ID, JobCheckInType.IN)).willReturn(true);
            JobCheckInEntity existing = mock(JobCheckInEntity.class);
            given(checkInRepository.findByJobContractIdAndType(CONTRACT_ID, JobCheckInType.IN))
                    .willReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.recordCheckIn(cmdIn("valid-jwt")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_CHECK_IN_ALREADY_EXISTS));
        }

        @Test
        @DisplayName("境界: 掛け持ち検出 → JOB_CHECK_IN_CONCURRENT_CONFLICT")
        void 掛け持ち_CONFLICT() {
            JobContractEntity contract = contractWith(JobContractStatus.MATCHED);
            JobPostingEntity posting = postingOpen();
            stubFindWithPolicy(contract, posting, JobCheckInType.IN, true);
            given(checkInRepository.existsByWorkerUserIdAndScannedAtBetweenAndTypeAndJobContractIdNot(
                    eq(WORKER_ID), any(), any(), eq(JobCheckInType.IN), eq(CONTRACT_ID)))
                    .willReturn(true);

            assertThatThrownBy(() -> service.recordCheckIn(cmdIn("valid-jwt")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_CHECK_IN_CONCURRENT_CONFLICT));
        }

        @Test
        @DisplayName("境界: JWT ペイロードの workerUserId が認証ユーザーと不一致 → JOB_QR_TOKEN_WRONG_WORKER")
        void JWT_Worker不一致() {
            JobContractEntity contract = contractWith(JobContractStatus.MATCHED);
            JobPostingEntity posting = postingOpen();
            stubFindWithPolicy(contract, posting, JobCheckInType.IN, true);
            // JWT は OTHER_WORKER_ID 向けに発行されたが、認証ユーザーは WORKER_ID。
            JobQrTokenEntity tokenEntity = tokenEntity();
            given(qrTokenService.verifyAndConsume(any(), any(), anyBool()))
                    .willReturn(new JobQrTokenService.VerifyResult(
                            tokenEntity, CONTRACT_ID, OTHER_WORKER_ID, JobCheckInType.IN, "nonce", "v1"));

            assertThatThrownBy(() -> service.recordCheckIn(cmdIn("valid-jwt")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_WRONG_WORKER));
        }

        @Test
        @DisplayName("境界: Geolocation 500m 超 → geo_anomaly=TRUE、チェックインは成立、JOB_GEO_ANOMALY 通知")
        void Geolocation_乖離_anomaly_true() {
            JobContractEntity contract = contractWith(JobContractStatus.MATCHED);
            JobPostingEntity posting = postingOpen();
            stubFindWithPolicy(contract, posting, JobCheckInType.IN, true);
            stubTokenVerify(contract, JobCheckInType.IN);
            given(geolocationService.isAnomaly(any(), any(), anyDouble())).willReturn(true);

            CheckInResult result = service.recordCheckIn(cmdIn("valid-jwt"));

            assertThat(result.geoAnomaly()).isTrue();
            // チェックインは成立。
            assertThat(contract.getStatus()).isEqualTo(JobContractStatus.IN_PROGRESS);
            // アラート通知が飛ぶ。
            verify(notificationService).notifyGeoAnomaly(eq(CONTRACT_ID), anyDouble());
        }

        @Test
        @DisplayName("境界: accuracy > 100m のとき isAnomaly はスキップ方向に効く（Service 側は mock 値を尊重）")
        void Geolocation_accuracy_高精度でも判定は委譲() {
            JobContractEntity contract = contractWith(JobContractStatus.MATCHED);
            JobPostingEntity posting = postingOpen();
            stubFindWithPolicy(contract, posting, JobCheckInType.IN, true);
            stubTokenVerify(contract, JobCheckInType.IN);
            // accuracy 高精度超過: GeolocationService 側で false を返す前提を mock で再現。
            given(geolocationService.isAnomaly(any(), eq(150.0), anyDouble())).willReturn(false);

            CheckInCommand cmd = new CheckInCommand(
                    CONTRACT_ID, WORKER_ID, "valid-jwt", null, JobCheckInType.IN, NOW,
                    false, false, 35.6586, 139.7454, 150.0, "ua");

            CheckInResult result = service.recordCheckIn(cmd);
            assertThat(result.geoAnomaly()).isFalse();
        }
    }

    // =====================================================================
    // OUT 成立
    // =====================================================================

    @Nested
    @DisplayName("recordCheckIn（OUT 成立）")
    class RecordCheckOutTests {

        @Test
        @DisplayName("正常系: IN_PROGRESS → CHECKED_OUT、workDurationMinutes が正確に計算される")
        void 正常_OUT成立_業務時間計算() {
            // IN の時刻を設定し、30 分後に OUT する契約を用意。
            Instant checkedInAt = NOW.minus(java.time.Duration.ofMinutes(30));
            JobContractEntity contract = contractWith(JobContractStatus.IN_PROGRESS);
            contract.recordCheckIn(checkedInAt);

            JobPostingEntity posting = postingOpen();
            stubFindWithPolicy(contract, posting, JobCheckInType.OUT, true);
            stubTokenVerify(contract, JobCheckInType.OUT);
            // OUT 時は IN レコード存在チェック → 存在
            JobCheckInEntity inRecord = mock(JobCheckInEntity.class);
            given(checkInRepository.findByJobContractIdAndType(CONTRACT_ID, JobCheckInType.IN))
                    .willReturn(Optional.of(inRecord));
            // OUT 自身は未存在
            given(checkInRepository.findByJobContractIdAndType(CONTRACT_ID, JobCheckInType.OUT))
                    .willReturn(Optional.empty());

            CheckInCommand cmd = cmdOut("valid-jwt", NOW);

            CheckInResult result = service.recordCheckIn(cmd);

            assertThat(contract.getStatus()).isEqualTo(JobContractStatus.CHECKED_OUT);
            assertThat(contract.getCheckedOutAt()).isEqualTo(NOW);
            assertThat(contract.getWorkDurationMinutes()).isEqualTo(30);
            assertThat(result.workDurationMinutes()).isEqualTo(30);
            assertThat(result.newStatus()).isEqualTo(JobContractStatus.CHECKED_OUT);

            verify(stateMachine).validate(JobContractStatus.IN_PROGRESS, JobContractStatus.CHECKED_OUT);
            verify(notificationService).notifyCheckedOut(CONTRACT_ID);
        }

        @Test
        @DisplayName("境界: IN レコード未登録で OUT → JOB_CHECK_OUT_BEFORE_CHECK_IN")
        void IN未登録_OUT拒否() {
            JobContractEntity contract = contractWith(JobContractStatus.IN_PROGRESS);
            given(contractRepository.findByIdForUpdate(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(jobPolicy.canRecordCheckIn(contract, WORKER_ID, JobCheckInType.OUT)).willReturn(true);
            // OUT 自身未存在 → ALREADY_EXISTS はスルー
            given(checkInRepository.findByJobContractIdAndType(CONTRACT_ID, JobCheckInType.OUT))
                    .willReturn(Optional.empty());
            // IN も未存在 → BEFORE_CHECK_IN
            given(checkInRepository.findByJobContractIdAndType(CONTRACT_ID, JobCheckInType.IN))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.recordCheckIn(cmdOut("valid-jwt", NOW)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_CHECK_OUT_BEFORE_CHECK_IN));
        }
    }

    // =====================================================================
    // 入力バリデーション
    // =====================================================================

    @Nested
    @DisplayName("入力バリデーション")
    class InputValidation {

        @Test
        @DisplayName("token と shortCode が両方 null/blank → JOB_QR_TOKEN_INVALID_SIGNATURE")
        void トークン未指定_INVALID_SIGNATURE() {
            CheckInCommand cmd = new CheckInCommand(
                    CONTRACT_ID, WORKER_ID, null, null, JobCheckInType.IN, NOW,
                    false, false, null, null, null, "ua");

            assertThatThrownBy(() -> service.recordCheckIn(cmd))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE));
        }

        @Test
        @DisplayName("契約不在 → JOB_CONTRACT_NOT_FOUND")
        void 契約不在_NOT_FOUND() {
            given(contractRepository.findByIdForUpdate(CONTRACT_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.recordCheckIn(cmdIn("valid-jwt")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));
        }
    }

    // ---------------------------------------------------------------------
    // ヘルパー（テストデータ・スタブ組立）
    // ---------------------------------------------------------------------

    private void stubFindWithPolicy(JobContractEntity contract, JobPostingEntity posting,
                                    JobCheckInType type, boolean allowed) {
        given(contractRepository.findByIdForUpdate(CONTRACT_ID)).willReturn(Optional.of(contract));
        given(jobPolicy.canRecordCheckIn(contract, WORKER_ID, type)).willReturn(allowed);
        given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
        // 重複チェック: IN/OUT とも未存在がデフォルト。
        given(checkInRepository.findByJobContractIdAndType(CONTRACT_ID, type))
                .willReturn(Optional.empty());
    }

    private void stubTokenVerify(JobContractEntity contract, JobCheckInType type) {
        JobQrTokenEntity tokenEntity = tokenEntity();
        given(qrTokenService.verifyAndConsume(any(), any(), anyBool()))
                .willReturn(new JobQrTokenService.VerifyResult(
                        tokenEntity, CONTRACT_ID, WORKER_ID, type, "nonce", "v1"));
    }

    private JobQrTokenEntity tokenEntity() {
        JobQrTokenEntity entity = JobQrTokenEntity.builder()
                .jobContractId(CONTRACT_ID)
                .type(JobCheckInType.IN)
                .nonce("nonce-xxx")
                .kid("v1")
                .shortCode("AB23CD")
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(NOW.plusSeconds(30))
                .issuedByUserId(REQUESTER_ID)
                .build();
        setId(entity, TOKEN_ID);
        return entity;
    }

    private CheckInCommand cmdIn(String token) {
        return cmdInAs(WORKER_ID, token);
    }

    private CheckInCommand cmdInAs(Long workerId, String token) {
        return new CheckInCommand(
                CONTRACT_ID, workerId, token, null, JobCheckInType.IN, NOW,
                false, false, 35.6586, 139.7454, 10.0, "ua/test");
    }

    private CheckInCommand cmdOut(String token, Instant scannedAt) {
        return new CheckInCommand(
                CONTRACT_ID, WORKER_ID, token, null, JobCheckInType.OUT, scannedAt,
                false, false, 35.6586, 139.7454, 10.0, "ua/test");
    }

    private JobContractEntity contractWith(JobContractStatus status) {
        // 掛け持ち判定のため workStartAt/workEndAt を NOW 基準で設定（JVM systemDefault 変換前提）。
        LocalDateTime workStart = LocalDateTime.ofInstant(NOW.minusSeconds(600), ZoneId.systemDefault());
        LocalDateTime workEnd = LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneId.systemDefault());
        JobContractEntity contract = JobContractEntity.builder()
                .jobPostingId(POSTING_ID)
                .jobApplicationId(3001L)
                .requesterUserId(REQUESTER_ID)
                .workerUserId(WORKER_ID)
                .baseRewardJpy(3000)
                .workStartAt(workStart)
                .workEndAt(workEnd)
                .status(status)
                .matchedAt(LocalDateTime.now())
                .rejectionCount(0)
                .build();
        setId(contract, CONTRACT_ID);
        return contract;
    }

    private JobPostingEntity postingOpen() {
        LocalDateTime now = LocalDateTime.now();
        JobPostingEntity entity = JobPostingEntity.builder()
                .teamId(10L)
                .createdByUserId(REQUESTER_ID)
                .title("テスト求人")
                .description("テスト内容")
                .workLocationType(WorkLocationType.ONSITE)
                .workAddress("東京都")
                .workStartAt(now.plusDays(1))
                .workEndAt(now.plusDays(1).plusHours(3))
                .rewardType(RewardType.LUMP_SUM)
                .baseRewardJpy(3000)
                .capacity(1)
                .applicationDeadlineAt(now.plusHours(12))
                .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                .status(JobPostingStatus.OPEN)
                .build();
        setId(entity, POSTING_ID);
        return entity;
    }

    /** Mockito の {@code anyBoolean()} を Java ジェネリクス安全に呼ぶためのエイリアス。 */
    private static boolean anyBool() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private void setId(Object entity, Long id) {
        try {
            Class<?> clazz = entity.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new IllegalStateException("id field not found on " + entity.getClass());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("id set failed", e);
        }
    }
}
