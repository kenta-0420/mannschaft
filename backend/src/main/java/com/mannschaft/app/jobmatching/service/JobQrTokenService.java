package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.config.QrSigningProperties;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobQrTokenEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobQrTokenRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * F13.1 Phase 13.1.2 — QR トークン発行／検証サービス。
 *
 * <p>責務（設計書 §2.3.1 / §10.10）:</p>
 * <ul>
 *   <li>{@link #issue(Long, JobCheckInType, Integer, Long)}:
 *       Requester 側デバイス向けに短命トークンを発行。HMAC-SHA256 署名の JWT 互換ペイロードと
 *       {@code nonce}／{@code shortCode}（手動入力フォールバック用 6 桁コード）を生成し、
 *       {@link JobQrTokenEntity} を永続化する。</li>
 *   <li>{@link #verifyAndConsume(String, Instant, boolean)}:
 *       Worker がスキャンした JWT を検証し、再利用防止のため PESSIMISTIC ロック下で {@code usedAt} を記録する。
 *       3 層防御（署名検証 / 期限判定 / 使い捨て検知）を実施。</li>
 *   <li>{@link #verifyShortCode(String, JobCheckInType, Instant)}:
 *       QR 読取失敗時の手動入力フォールバックで短コードを検証・消費する。</li>
 *   <li>{@link #getCurrent(Long, JobCheckInType, Long)}:
 *       Requester 画面の QR 再表示で「現在有効な未消費トークン」を取得する（発行はしない、読取専用）。</li>
 * </ul>
 *
 * <p>本サービスはステータス遷移 (MATCHED → CHECKED_IN 等) には関与しない。遷移は
 * {@code JobCheckInService}（足軽参担当）が {@code JobContractStateMachine} と協働して行う。</p>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class JobQrTokenService {

    /** 短コードに使う文字セット（紛らわしい {@code 0 O 1 l I} を除外）。 */
    private static final char[] SHORT_CODE_ALPHABET =
            "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    /** 短コード発行時の衝突回避リトライ上限（SecureRandom で十分稀だが保険）。 */
    private static final int SHORT_CODE_MAX_ATTEMPTS = 10;

    private final JobQrTokenRepository qrTokenRepository;
    private final JobContractRepository contractRepository;
    private final QrSigningKeyProvider keyProvider;
    private final QrSigningProperties properties;
    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();

    // ---------------------------------------------------------------------
    // 発行
    // ---------------------------------------------------------------------

    /**
     * Requester 側デバイス向けに QR トークンを発行する。
     *
     * @param contractId   対象契約 ID
     * @param type         IN / OUT 種別
     * @param ttlSeconds   TTL（秒）。{@code null} ならデフォルト（60 秒）、上限は 300 秒でクランプ
     * @param issuerUserId 発行者（通常は Requester 本人）
     * @return 発行結果（QR 画像化する {@code token} 文字列 + 手動入力用 {@code shortCode}）
     * @throws BusinessException 契約が存在しない、または発行者が Requester でない場合
     */
    public IssueResult issue(Long contractId, JobCheckInType type, Integer ttlSeconds, Long issuerUserId) {
        Objects.requireNonNull(contractId, "contractId は必須");
        Objects.requireNonNull(type, "type は必須");
        Objects.requireNonNull(issuerUserId, "issuerUserId は必須");

        JobContractEntity contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));

        // 発行権限: Requester 本人のみ。第三者や Worker 本人からの発行は許可しない。
        if (!Objects.equals(contract.getRequesterUserId(), issuerUserId)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        // TTL のクランプ。リクエストに従うが上限を超えないよう min() で抑える。
        int effectiveTtl = resolveTtl(ttlSeconds);

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plusSeconds(effectiveTtl);

        String nonce = UUID.randomUUID().toString();
        String shortCode = generateUniqueShortCode(type);

        QrSigningKeyProvider.CurrentKey currentKey = keyProvider.current();
        String jwt = buildJwt(contract, type, nonce, currentKey, now, expiresAt);

        JobQrTokenEntity entity = JobQrTokenEntity.builder()
                .jobContractId(contractId)
                .type(type)
                .nonce(nonce)
                .kid(currentKey.kid())
                .shortCode(shortCode)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .issuedByUserId(issuerUserId)
                .build();
        qrTokenRepository.save(entity);

        log.debug("QR トークン発行: contractId={}, type={}, kid={}, ttl={}s, nonce={}",
                contractId, type, currentKey.kid(), effectiveTtl, nonce);

        return new IssueResult(jwt, shortCode, type, now, expiresAt, nonce, currentKey.kid());
    }

    // ---------------------------------------------------------------------
    // 検証（JWT 経由）
    // ---------------------------------------------------------------------

    /**
     * Worker がスキャンした JWT トークンを検証し、{@code usedAt} を記録する。
     *
     * <p>手順:</p>
     * <ol>
     *   <li>JWT ヘッダから {@code kid} を抽出し、{@link QrSigningKeyProvider#find(String)} で鍵解決</li>
     *   <li>{@link Jwts#parser()}{@code .verifyWith(key).build().parseSignedClaims(...)} で署名・有効期限を検証</li>
     *   <li>{@code nonce} で {@link JobQrTokenRepository#findByNonce(String)} に PESSIMISTIC_WRITE ロック取得</li>
     *   <li>{@link JobQrTokenEntity#isUsed()} チェック（再利用なら {@code JOB_QR_TOKEN_REUSED}）</li>
     *   <li>オンライン送信時は {@link JobQrTokenEntity#isExpired(Instant)} チェック。
     *       オフライン送信時は {@link JobQrTokenEntity#isWithinIssuedAndExpires(Instant)} で
     *       スキャン時刻が有効範囲内であれば許可</li>
     *   <li>{@link JobQrTokenEntity#markUsed(Instant)} して永続化</li>
     * </ol>
     *
     * @param token              Worker スマホから送信された JWT 文字列
     * @param scannedAt          クライアントでスキャンした時刻（オフライン送信時に使用）
     * @param offlineSubmitted   オフライン送信フラグ（PWA IndexedDB 経由のリプレイ）
     */
    public VerifyResult verifyAndConsume(String token, Instant scannedAt, boolean offlineSubmitted) {
        Objects.requireNonNull(token, "token は必須");
        Objects.requireNonNull(scannedAt, "scannedAt は必須");

        // --- 署名検証 ---
        Claims claims = parseAndVerifyJwt(token);

        String nonce = claims.get("nonce", String.class);
        Long contractId = claims.get("cid", Long.class);
        Long workerUserId = claims.get("wid", Long.class);
        String typeStr = claims.get("typ", String.class);
        String kid = resolveKidFromToken(token);

        if (nonce == null || contractId == null || workerUserId == null || typeStr == null) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE);
        }
        JobCheckInType type;
        try {
            type = JobCheckInType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE, e);
        }

        // --- nonce 行取得（PESSIMISTIC_WRITE ロック下で） ---
        JobQrTokenEntity entity = qrTokenRepository.findByNonce(nonce)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE));

        // --- 再利用検知 ---
        if (entity.isUsed()) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_REUSED);
        }

        // --- 期限判定（オンライン/オフラインで分岐） ---
        Instant now = Instant.now(clock);
        if (offlineSubmitted) {
            // オフライン送信: スキャン時刻が発行〜失効の有効範囲内なら許可（サーバー時刻はチェックしない）
            if (!entity.isWithinIssuedAndExpires(scannedAt)) {
                throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_EXPIRED);
            }
        } else {
            // オンライン送信: サーバー現在時刻で失効判定
            if (entity.isExpired(now)) {
                throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_EXPIRED);
            }
        }

        // --- 消費マーク ---
        entity.markUsed(now);
        qrTokenRepository.save(entity);

        log.debug("QR トークン検証成功: nonce={}, contractId={}, type={}, offlineSubmitted={}",
                nonce, contractId, type, offlineSubmitted);

        return new VerifyResult(entity, contractId, workerUserId, type, nonce, kid);
    }

    // ---------------------------------------------------------------------
    // 検証（短コード経由）
    // ---------------------------------------------------------------------

    /**
     * 手動入力フォールバック用の短コードを検証・消費する。
     *
     * @param shortCode Worker が画面入力した 6 桁コード
     * @param type      IN / OUT 種別
     * @param scannedAt スキャン時刻（本メソッドでは使用しないが、将来のオフライン対応向けにシグネチャで受ける）
     */
    public VerifyResult verifyShortCode(String shortCode, JobCheckInType type, Instant scannedAt) {
        Objects.requireNonNull(shortCode, "shortCode は必須");
        Objects.requireNonNull(type, "type は必須");
        Objects.requireNonNull(scannedAt, "scannedAt は必須");

        Instant now = Instant.now(clock);
        JobQrTokenEntity entity = qrTokenRepository
                .findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(shortCode, type, now)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_QR_SHORT_CODE_NOT_FOUND));

        // レース条件で他スレッドが直前に消費する可能性があるため再チェック
        if (entity.isUsed()) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_REUSED);
        }

        entity.markUsed(now);
        qrTokenRepository.save(entity);

        log.debug("短コード検証成功: shortCode={}, contractId={}, type={}",
                shortCode, entity.getJobContractId(), type);

        return new VerifyResult(entity, entity.getJobContractId(), null, type, entity.getNonce(), entity.getKid());
    }

    // ---------------------------------------------------------------------
    // 現在有効なトークン取得（Requester 画面再表示用）
    // ---------------------------------------------------------------------

    /**
     * Requester 画面の QR 再表示で「現在有効な未消費トークン」を取得する。
     *
     * <p>新規発行はしない（発行は {@link #issue(Long, JobCheckInType, Integer, Long)} を別途呼ぶ）。
     * ローテーション制御（失効間近なら新規発行、残り時間があれば使い回し）は Controller 側の責務。</p>
     *
     * @param contractId   対象契約 ID
     * @param type         IN / OUT 種別
     * @param issuerUserId 閲覧者（Requester 本人）
     * @return 現在有効なトークン（無ければ empty）
     * @throws BusinessException 契約不在、または閲覧者が Requester でない場合
     */
    @Transactional(readOnly = true)
    public Optional<JobQrTokenEntity> getCurrent(Long contractId, JobCheckInType type, Long issuerUserId) {
        Objects.requireNonNull(contractId, "contractId は必須");
        Objects.requireNonNull(type, "type は必須");
        Objects.requireNonNull(issuerUserId, "issuerUserId は必須");

        JobContractEntity contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));
        if (!Objects.equals(contract.getRequesterUserId(), issuerUserId)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        Instant now = Instant.now(clock);
        return qrTokenRepository
                .findTopByJobContractIdAndTypeAndUsedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(
                        contractId, type, now);
    }

    // ---------------------------------------------------------------------
    // private ヘルパー
    // ---------------------------------------------------------------------

    private int resolveTtl(Integer requested) {
        int base = requested != null ? requested : properties.getTtlSeconds();
        if (base < 1) {
            base = properties.getTtlSeconds();
        }
        return Math.min(base, properties.getTtlSecondsMax());
    }

    /**
     * 短コードを生成する（紛らわしい文字を除外、未使用＆未失効範囲で衝突回避リトライ付き）。
     */
    private String generateUniqueShortCode(JobCheckInType type) {
        Instant now = Instant.now(clock);
        int length = properties.getShortCodeLength();

        for (int attempt = 0; attempt < SHORT_CODE_MAX_ATTEMPTS; attempt++) {
            String candidate = randomShortCode(length);
            // 同一 type かつ未消費・未失効の範囲で衝突していなければ OK（他 type との重複は問題ない）
            boolean conflicts = qrTokenRepository
                    .findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(candidate, type, now)
                    .isPresent();
            if (!conflicts) {
                return candidate;
            }
        }
        // 実運用では SecureRandom + 31^6 空間でここに到達しないはず（保険）
        throw new IllegalStateException(
                "短コード生成に " + SHORT_CODE_MAX_ATTEMPTS + " 回連続失敗しました（要調査）");
    }

    private String randomShortCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SHORT_CODE_ALPHABET[secureRandom.nextInt(SHORT_CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * JWT を組み立てる。
     *
     * <p>ペイロード（claims）:</p>
     * <ul>
     *   <li>{@code cid} — 契約 ID</li>
     *   <li>{@code wid} — 採用確定 Worker の user_id（検証時に照合）</li>
     *   <li>{@code typ} — {@link JobCheckInType}.name()（{@code IN} or {@code OUT}）</li>
     *   <li>{@code nonce} — リプレイ防止ランダム ID</li>
     *   <li>{@code iat} / {@code exp} — 発行時刻 / 失効時刻</li>
     * </ul>
     *
     * <p>ヘッダには {@code kid} を埋め込み、検証側で鍵解決に使用する。</p>
     */
    private String buildJwt(JobContractEntity contract, JobCheckInType type, String nonce,
                            QrSigningKeyProvider.CurrentKey currentKey, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .header().keyId(currentKey.kid()).and()
                .claim("cid", contract.getId())
                .claim("wid", contract.getWorkerUserId())
                .claim("typ", type.name())
                .claim("nonce", nonce)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(currentKey.key(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * JWT の署名を検証し Claims を返す。
     *
     * <p>検証手順: ヘッダから {@code kid} を取り出し → {@link QrSigningKeyProvider#find(String)} で鍵解決
     * → {@code verifyWith(key)} で署名＆期限チェック。失敗はすべて
     * {@link JobmatchingErrorCode#JOB_QR_TOKEN_INVALID_SIGNATURE} にマッピング（攻撃者への情報量を絞る）。</p>
     *
     * <p>ただし、JJWT が {@code exp} 超過で自発的に {@code ExpiredJwtException} を投げた場合は
     * 攻撃ではなくオンライン送信の TTL 切れであり、{@link JobmatchingErrorCode#JOB_QR_TOKEN_EXPIRED} を返す。</p>
     */
    private Claims parseAndVerifyJwt(String token) {
        String kid;
        try {
            kid = resolveKidFromToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE, e);
        }
        SecretKey key = keyProvider.find(kid)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE));

        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            // JJWT 標準の有効期限超過。オンライン送信の通常 TTL 切れ扱い。
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_EXPIRED, e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE, e);
        }
    }

    /**
     * JWT のヘッダから {@code kid} を取り出す（署名検証前に必要）。
     *
     * <p>JJWT 0.12.x では {@code Jwts.parser().build().parseUnsecuredClaims(...)} は署名付き JWS に対して動かないため、
     * ヘッダ（base64url 第 1 セグメント）を手動でデコードして Jackson で読む方式を取る。</p>
     */
    private String resolveKidFromToken(String token) {
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) {
            throw new MalformedJwtException("JWT 形式が不正です（ヘッダ区切り無し）");
        }
        String headerB64 = token.substring(0, firstDot);
        byte[] headerBytes;
        try {
            headerBytes = java.util.Base64.getUrlDecoder().decode(headerB64);
        } catch (IllegalArgumentException e) {
            throw new MalformedJwtException("JWT ヘッダの Base64 デコードに失敗しました", e);
        }

        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(headerBytes);
            com.fasterxml.jackson.databind.JsonNode kidNode = node.get(JwsHeader.KEY_ID);
            if (kidNode == null || !kidNode.isTextual()) {
                throw new MalformedJwtException("JWT ヘッダに kid クレームがありません");
            }
            return kidNode.asText();
        } catch (java.io.IOException e) {
            throw new MalformedJwtException("JWT ヘッダのパースに失敗しました", e);
        }
    }

    // ---------------------------------------------------------------------
    // レコード型（戻り値）
    // ---------------------------------------------------------------------

    /**
     * {@link #issue} の結果。{@code token} を QR 画像化し、{@code shortCode} を口頭伝達用に画面表示する。
     */
    public record IssueResult(String token, String shortCode, JobCheckInType type,
                              Instant issuedAt, Instant expiresAt, String nonce, String kid) {
    }

    /**
     * {@link #verifyAndConsume} / {@link #verifyShortCode} の結果。
     *
     * <p>{@code workerUserId} は JWT 経由検証時のみ設定され、短コード経由検証時は {@code null}
     * （短コードはペイロードを持たないため、呼び出し側で契約の {@code workerUserId} を参照する）。</p>
     */
    public record VerifyResult(JobQrTokenEntity token, Long contractId, Long workerUserId,
                               JobCheckInType type, String nonce, String kid) {
    }
}
