package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.config.QrSigningProperties;
import com.mannschaft.app.jobmatching.entity.JobCheckInEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobCheckInRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.command.CheckInCommand;
import com.mannschaft.app.jobmatching.service.command.CheckInResult;
import com.mannschaft.app.jobmatching.state.JobContractStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * F13.1 Phase 13.1.2 — QR チェックイン／アウト記録サービス。
 *
 * <p>Worker がスキャンした QR トークン（または手動入力短コード）を検証し、
 * {@link JobCheckInEntity} の INSERT と契約ステータス遷移を原子的に実行する。</p>
 *
 * <h3>主な責務</h3>
 * <ul>
 *   <li>認可チェック（{@link JobPolicy#canRecordCheckIn} — Worker 本人 + 適切なステータス）</li>
 *   <li>重複検知（同一契約・同 type の {@link JobCheckInEntity} 既存有無）</li>
 *   <li>OUT 時の前提検証（IN レコードが存在すること）</li>
 *   <li>掛け持ち検出（同一 Worker が他契約で同時刻 IN 済）</li>
 *   <li>トークン検証（{@link JobQrTokenService} へ委譲）</li>
 *   <li>Geolocation 乖離判定（{@link GeolocationService} と業務場所の緯度経度を照合）</li>
 *   <li>{@link JobCheckInEntity} INSERT + 契約ステータス遷移（IN は CHECKED_IN→IN_PROGRESS の二段）</li>
 *   <li>通知発火（{@link JobNotificationService#notifyCheckedIn} ほか）</li>
 * </ul>
 *
 * <h3>トランザクション方針</h3>
 *
 * <p>{@link Transactional} によりチェックイン全体を 1 トランザクションで包む。契約行は
 * {@link JobContractRepository#findByIdForUpdate(Long)} で PESSIMISTIC_WRITE を取得し、
 * 同一契約に対する二重スキャンや他オペレーションとの競合を物理排他する。</p>
 *
 * <p>QR トークンの消費（{@link JobQrTokenService#verifyAndConsume}）側も別トランザクション境界を
 * 持つが、検証・消費は Service 層で同一スレッド内に直列で呼ぶため、外側の契約ロックと合わせて
 * リプレイ防止が成立する（nonce の UNIQUE も DB 側で担保）。</p>
 *
 * <p>通知送信は try/catch で呑み込み、業務トランザクションの整合性を優先する
 * （{@link JobContractService} の既存パターンと整合）。</p>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class JobCheckInService {

    /**
     * 掛け持ち検出の時間余裕（前後 1 時間）。契約の {@code workStartAt} - 1h 〜 {@code workEndAt} + 1h の
     * 範囲で他契約 IN の有無を確認する。過度に狭いと早め着席／残業のケースを誤検出せず、
     * 過度に広いと隣接業務を不当に阻害するため、実運用で調整可能とする余地を残す設計（現状は定数）。
     */
    static final Duration CONCURRENT_CHECK_BUFFER = Duration.ofHours(1);

    private final JobContractRepository contractRepository;
    private final JobCheckInRepository checkInRepository;
    private final JobPostingRepository postingRepository;
    private final JobQrTokenService qrTokenService;
    private final GeolocationService geolocationService;
    private final JobContractStateMachine stateMachine;
    private final JobPolicy jobPolicy;
    private final JobNotificationService notificationService;
    private final QrSigningProperties qrProperties;
    private final Clock clock;

    /**
     * チェックイン／アウトを記録する（Phase 13.1.2 中核）。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>{@link JobContractRepository#findByIdForUpdate} で契約を PESSIMISTIC_WRITE 取得</li>
     *   <li>{@link JobPolicy#canRecordCheckIn} による認可（Worker 本人 + ステータス整合）</li>
     *   <li>同一契約・同 type の既存 {@link JobCheckInEntity} 重複検知</li>
     *   <li>OUT 時は IN レコード存在を確認</li>
     *   <li>同一 Worker の他契約 IN（同時刻）を検出し、掛け持ちを拒否</li>
     *   <li>{@link JobQrTokenService} で JWT または短コードを検証・消費</li>
     *   <li>JWT 経由の場合は {@code workerUserId} 照合（ペイロードと認証ユーザーの一致）</li>
     *   <li>業務場所との Geolocation 乖離を計算し {@code geo_anomaly} を決定</li>
     *   <li>{@link JobCheckInEntity} を INSERT</li>
     *   <li>契約ステータスを遷移（IN: MATCHED→CHECKED_IN→IN_PROGRESS の二段、OUT: IN_PROGRESS→CHECKED_OUT）</li>
     *   <li>必要に応じて通知発火</li>
     * </ol>
     *
     * @param cmd Controller 層から渡された入力コマンド
     * @return チェックイン成立結果（{@link CheckInResult}）
     * @throws BusinessException 各種検証エラー（不正トークン、重複、掛け持ち等）
     */
    public CheckInResult recordCheckIn(CheckInCommand cmd) {
        Objects.requireNonNull(cmd, "cmd は必須");
        Objects.requireNonNull(cmd.contractId(), "contractId は必須");
        Objects.requireNonNull(cmd.workerUserId(), "workerUserId は必須");
        Objects.requireNonNull(cmd.type(), "type は必須");
        Objects.requireNonNull(cmd.scannedAt(), "scannedAt は必須");
        if ((cmd.token() == null || cmd.token().isBlank())
                && (cmd.shortCode() == null || cmd.shortCode().isBlank())) {
            // token / shortCode いずれも未指定は形式エラー（Controller 側の @Valid でも落ちる想定）。
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE);
        }

        // 1. 契約を PESSIMISTIC_WRITE で取得。
        JobContractEntity contract = contractRepository.findByIdForUpdate(cmd.contractId())
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));

        // 2. 認可（Worker 本人 + ステータス整合）。
        if (!jobPolicy.canRecordCheckIn(contract, cmd.workerUserId(), cmd.type())) {
            // Worker 不一致／不適切なステータスのいずれも原因混在を避け、ここでは WRONG_WORKER に寄せる
            // （攻撃者に対する情報量を絞る。ステータス不整合であっても「本契約の Worker でない」相当とみなす）。
            // ただしステータス不整合のうち「既に IN 済みで OUT を待つ状態での再 IN 試行」等は
            // 後段の重複検知で ALREADY_EXISTS として返す。
            if (!Objects.equals(contract.getWorkerUserId(), cmd.workerUserId())) {
                throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_WRONG_WORKER);
            }
            // ステータス起因の不許可は INVALID_STATE_TRANSITION で返す（Controller で 409 にマップ可能）。
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }

        // 3. 重複検知（同一契約・同 type は各 1 件まで、DB UNIQUE でも担保されているがここで先回り）。
        Optional<JobCheckInEntity> existing = checkInRepository
                .findByJobContractIdAndType(cmd.contractId(), cmd.type());
        if (existing.isPresent()) {
            throw new BusinessException(JobmatchingErrorCode.JOB_CHECK_IN_ALREADY_EXISTS);
        }

        // 4. OUT 時は IN レコード存在を確認。
        if (cmd.type() == JobCheckInType.OUT) {
            boolean inExists = checkInRepository
                    .findByJobContractIdAndType(cmd.contractId(), JobCheckInType.IN).isPresent();
            if (!inExists) {
                throw new BusinessException(JobmatchingErrorCode.JOB_CHECK_OUT_BEFORE_CHECK_IN);
            }
        }

        // 5. 掛け持ち検出（IN 時のみ。OUT は同時刻他契約に影響を与えない）。
        if (cmd.type() == JobCheckInType.IN) {
            detectConcurrentConflict(contract, cmd.workerUserId());
        }

        // 6. トークン検証。JWT 優先、短コードはフォールバック。
        JobQrTokenService.VerifyResult verifyResult = verifyToken(cmd);

        // 7. JWT 経由の場合は workerUserId 照合。短コードは JWT ペイロードを持たないため
        //    認証済み Worker 本人であることをもって信頼する（認可は既に通過済み）。
        if (verifyResult.workerUserId() != null
                && !Objects.equals(verifyResult.workerUserId(), cmd.workerUserId())) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_WRONG_WORKER);
        }
        // 種別（IN/OUT）の一致も必須。別種トークンで他オペレーションを試みるのは拒否。
        if (verifyResult.type() != cmd.type()) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE);
        }
        // 契約 ID の一致も確認（短コードは契約に紐付いているが念のため二重検証）。
        if (!Objects.equals(verifyResult.contractId(), cmd.contractId())) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE);
        }

        // 8. Geolocation 乖離判定。
        boolean geoAnomaly = evaluateGeoAnomaly(contract, cmd);

        // 9. JobCheckInEntity INSERT。
        Instant now = Instant.now(clock);
        JobCheckInEntity checkIn = JobCheckInEntity.builder()
                .jobContractId(cmd.contractId())
                .workerUserId(cmd.workerUserId())
                .type(cmd.type())
                .qrTokenId(verifyResult.token() != null ? verifyResult.token().getId() : null)
                .scannedAt(cmd.scannedAt())
                .serverReceivedAt(now)
                .offlineSubmitted(cmd.offlineSubmitted())
                .manualCodeFallback(cmd.manualCodeFallback())
                .geolocationLat(toBigDecimal(cmd.geoLat()))
                .geolocationLng(toBigDecimal(cmd.geoLng()))
                .geolocationAccuracyM(toAccuracyBigDecimal(cmd.geoAccuracy()))
                .geoAnomaly(geoAnomaly)
                .clientUserAgent(cmd.clientUserAgent())
                .build();
        if (geoAnomaly) {
            checkIn.markGeoAnomaly();
        }
        JobCheckInEntity savedCheckIn = checkInRepository.save(checkIn);

        // 10. 契約ステータス遷移。
        JobContractStatus newStatus = applyStatusTransition(contract, cmd);
        JobContractEntity savedContract = contractRepository.save(contract);

        // 11. 通知発火（失敗時はログのみ、業務 Tx は継続）。
        fireNotifications(savedContract, cmd, geoAnomaly, distanceOrNull(contract, cmd));

        log.info("QR チェックイン記録: contractId={}, workerId={}, type={}, newStatus={}, "
                        + "offlineSubmitted={}, manualCodeFallback={}, geoAnomaly={}",
                cmd.contractId(), cmd.workerUserId(), cmd.type(), newStatus,
                cmd.offlineSubmitted(), cmd.manualCodeFallback(), geoAnomaly);

        return new CheckInResult(
                savedCheckIn.getId(),
                cmd.contractId(),
                cmd.type(),
                newStatus,
                savedContract.getWorkDurationMinutes(),
                geoAnomaly
        );
    }

    // ---------------------------------------------------------------------
    // private ヘルパー
    // ---------------------------------------------------------------------

    /**
     * 掛け持ち検出。契約の業務時間帯（前後 {@link #CONCURRENT_CHECK_BUFFER} を持たせた範囲）に、
     * 当該 Worker が**別契約で** IN スキャン済の場合、拒否する（設計書 §2.3.1 末尾）。
     *
     * <p>契約の {@code workStartAt/workEndAt} は {@link java.time.LocalDateTime} で、UTC 前提の
     * Instant への変換はシステムデフォルトゾーンを用いる（DB と JVM で同ゾーン運用前提）。</p>
     */
    private void detectConcurrentConflict(JobContractEntity contract, Long workerUserId) {
        // 契約の workStartAt/workEndAt は LocalDateTime（JVM システムゾーン前提）。
        // Clock が UTC 設定であっても、契約上の時刻は DB 格納時の JVM ローカルゾーンで解釈するのが
        // 既存 Entity 運用と整合する。このため systemDefault() で Instant 化する。
        ZoneId zone = ZoneId.systemDefault();
        Instant rangeFrom = contract.getWorkStartAt().atZone(zone).toInstant()
                .minus(CONCURRENT_CHECK_BUFFER);
        Instant rangeTo = contract.getWorkEndAt().atZone(zone).toInstant()
                .plus(CONCURRENT_CHECK_BUFFER);
        boolean conflicts = checkInRepository
                .existsByWorkerUserIdAndScannedAtBetweenAndTypeAndJobContractIdNot(
                        workerUserId, rangeFrom, rangeTo, JobCheckInType.IN, contract.getId());
        if (conflicts) {
            throw new BusinessException(JobmatchingErrorCode.JOB_CHECK_IN_CONCURRENT_CONFLICT);
        }
    }

    /**
     * トークン検証。JWT が指定されていれば {@link JobQrTokenService#verifyAndConsume} を、
     * なければ {@link JobQrTokenService#verifyShortCode} を呼ぶ。
     */
    private JobQrTokenService.VerifyResult verifyToken(CheckInCommand cmd) {
        if (cmd.token() != null && !cmd.token().isBlank()) {
            return qrTokenService.verifyAndConsume(cmd.token(), cmd.scannedAt(), cmd.offlineSubmitted());
        }
        return qrTokenService.verifyShortCode(cmd.shortCode(), cmd.type(), cmd.scannedAt());
    }

    /**
     * Geolocation 乖離判定。業務場所の緯度経度が {@link JobPostingEntity} に無い場合は常に false。
     *
     * <p>現時点の {@link JobPostingEntity} は緯度経度カラムを持たないため、設計書の指示に従い
     * 「{@code latitude=null} → 距離判定 null（anomaly 判定もスキップ）」で常に false を返す。
     * 将来カラム追加時は本メソッドを {@link GeolocationService#distanceMeters} 経由に差し替える。</p>
     */
    private boolean evaluateGeoAnomaly(JobContractEntity contract, CheckInCommand cmd) {
        Double distance = distanceOrNull(contract, cmd);
        return geolocationService.isAnomaly(distance, cmd.geoAccuracy(),
                qrProperties.getAnomalyDistanceMeters());
    }

    /**
     * 業務場所との距離を計算して返す。業務場所緯度経度が取れない場合や、端末位置が
     * 不明な場合は {@code null} を返す（判定スキップ）。
     */
    private Double distanceOrNull(JobContractEntity contract, CheckInCommand cmd) {
        if (cmd.geoLat() == null || cmd.geoLng() == null) {
            return null;
        }
        Optional<JobPostingEntity> postingOpt = postingRepository.findById(contract.getJobPostingId());
        if (postingOpt.isEmpty()) {
            return null;
        }
        // JobPostingEntity はまだ緯度経度カラムを持たないため、ここではリフレクション不要で
        // 常に null → 距離計算不可（judgment skip）。将来カラム追加時に直接参照に切り替える。
        Double postingLat = null;
        Double postingLng = null;
        return geolocationService.distanceMeters(postingLat, postingLng, cmd.geoLat(), cmd.geoLng())
                .orElse(null);
    }

    /**
     * 契約ステータスを遷移させる。遷移前に {@link JobContractStateMachine#validate} を通す。
     *
     * <p>IN 時: {@code MATCHED → CHECKED_IN → IN_PROGRESS} の二段（{@code markInProgressAfterCheckIn}）。
     * CHECKED_IN で停止している契約（中間障害時の再送等）は既に IN_PROGRESS へ進めないため
     * {@code canRecordCheckIn} で弾かれているはずだが、念のため現状ステータス別に分岐する。</p>
     *
     * <p>OUT 時: {@code IN_PROGRESS → CHECKED_OUT}（{@code markCheckedOut}）。
     * {@link JobContractEntity#recordCheckOut(Instant)} で {@code workDurationMinutes} も同時に計算する。</p>
     */
    private JobContractStatus applyStatusTransition(JobContractEntity contract, CheckInCommand cmd) {
        if (cmd.type() == JobCheckInType.IN) {
            // MATCHED → CHECKED_IN → IN_PROGRESS の二段。
            stateMachine.validate(contract.getStatus(), JobContractStatus.CHECKED_IN);
            stateMachine.validate(JobContractStatus.CHECKED_IN, JobContractStatus.IN_PROGRESS);
            contract.recordCheckIn(cmd.scannedAt());
            contract.markInProgressAfterCheckIn();
            return JobContractStatus.IN_PROGRESS;
        }
        // OUT: IN_PROGRESS → CHECKED_OUT。
        stateMachine.validate(contract.getStatus(), JobContractStatus.CHECKED_OUT);
        contract.recordCheckOut(cmd.scannedAt());
        contract.markCheckedOut();
        return JobContractStatus.CHECKED_OUT;
    }

    /**
     * 通知発火。IN 成立時は JOB_CHECKED_IN、OUT 成立時は JOB_CHECKED_OUT、
     * geo_anomaly が立っていれば JOB_GEO_ANOMALY も併送する。
     */
    private void fireNotifications(JobContractEntity contract, CheckInCommand cmd,
                                   boolean geoAnomaly, Double distance) {
        try {
            if (cmd.type() == JobCheckInType.IN) {
                notificationService.notifyCheckedIn(contract.getId());
            } else {
                notificationService.notifyCheckedOut(contract.getId());
            }
        } catch (Exception e) {
            log.warn("チェックイン/アウト通知失敗: contractId={}, type={}, error={}",
                    contract.getId(), cmd.type(), e.getMessage());
        }
        if (geoAnomaly) {
            try {
                notificationService.notifyGeoAnomaly(contract.getId(),
                        distance != null ? distance : -1.0);
            } catch (Exception e) {
                log.warn("Geolocation 乖離通知失敗: contractId={}, error={}",
                        contract.getId(), e.getMessage());
            }
        }
    }

    /** {@code Double} → {@code BigDecimal(scale=6)}。null はそのまま null。 */
    private static BigDecimal toBigDecimal(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    /** 精度（メートル）用 {@code BigDecimal(scale=2)}。 */
    private static BigDecimal toAccuracyBigDecimal(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
