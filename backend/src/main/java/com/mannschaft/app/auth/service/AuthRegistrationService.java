package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.service.BetaRestrictionService;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.event.EmailVerificationResentEvent;
import com.mannschaft.app.auth.event.EmailVerifiedEvent;
import com.mannschaft.app.auth.event.UserRegisteredEvent;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.util.PasswordPolicyValidator;
import com.mannschaft.app.auth.util.SecureTokenGenerator;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.util.AgeGroupCalculator;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.weather.event.UserPostalCodeUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * ユーザー登録 / メール認証サービス。
 * 登録時のレートリミット・ベータ制限・パスワードポリシー検証・メール認証トークン生成と、
 * メール認証トークン検証 / 認証メール再送を担う。
 *
 * <p>セキュリティ重要: 全メソッドのロジック・トランザクション境界・イベント発行タイミング・
 * エラーコードは AuthService から移送した byte-identical な実装である。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AuthRegistrationService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final AuthTokenService authTokenService;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final EncryptionService encryptionService;
    private final BetaRestrictionService betaRestrictionService;
    private final InviteService inviteService;

    // レートリミット設定
    private static final int REGISTER_MAX_ATTEMPTS = 10;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);

    private static final String EMAIL_VERIFY_COOLDOWN_PREFIX = "mannschaft:auth:email_verify_cooldown:";

    // トークン有効期限
    private static final Duration EMAIL_VERIFICATION_EXPIRY = Duration.ofHours(24);

    // ========================================
    // 登録
    // ========================================

    /**
     * ユーザー登録を行う。
     * レートリミット確認 → email重複チェック → パスワードポリシー検証 → ユーザー作成 →
     * メール認証トークン生成 → イベント発行。
     *
     * @param req       登録リクエスト
     * @param ipAddress リクエスト元IPアドレス
     * @return 登録完了メッセージ
     */
    @Transactional
    // TODO: authドメインとroleドメインをまたいでいる（InviteService.joinByInviteを直接呼び出し）。将来はUserRegisteredEventで分離予定
    public ApiResponse<MessageResponse> register(RegisterRequest req, String ipAddress) {
        // 1. レートリミットチェック
        String rateLimitKey = "mannschaft:auth:register_attempt:" + ipAddress;
        authTokenService.checkRateLimit(rateLimitKey, REGISTER_MAX_ATTEMPTS, REGISTER_WINDOW);

        // 1.5. ベータ制限チェック
        if (betaRestrictionService.isEnabled()) {
            if (req.getInviteToken() == null || req.getInviteToken().isBlank()) {
                throw new BusinessException(AuthErrorCode.AUTH_042);
            }
            if (!betaRestrictionService.isBetaTokenValid(req.getInviteToken())) {
                throw new BusinessException(AuthErrorCode.AUTH_043);
            }
        }

        // 2. email重複チェック（論理削除済みユーザーも含めて確認）
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new BusinessException(AuthErrorCode.AUTH_004);
        }
        Optional<UserEntity> deletedUserOpt = userRepository.findByEmailIncludingDeleted(req.getEmail());
        if (deletedUserOpt.isPresent() && deletedUserOpt.get().getDeletedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_041);
        }

        // 3. パスワードポリシー検証
        PasswordPolicyValidator.validate(req.getPassword());

        // 3.5. birth_date バリデーション（F01.9）
        if (req.getBirthDate() == null || req.getBirthDate().isBlank()) {
            throw new BusinessException(AuthErrorCode.AUTH_050);
        }
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(req.getBirthDate());
        } catch (DateTimeParseException e) {
            throw new BusinessException(AuthErrorCode.AUTH_051);
        }
        LocalDate today = LocalDate.now(TimezoneContextHolder.get());
        if (birthDate.isAfter(today)) {
            throw new BusinessException(AuthErrorCode.AUTH_052);
        }
        if (birthDate.isBefore(today.minusYears(100))) {
            throw new BusinessException(AuthErrorCode.AUTH_053);
        }

        // 4. ユーザー作成
        // displayName は users.display_name（NOT NULL）に対応する。nickname は任意入力のため、
        // 未指定（null / 空白）の場合は氏名から表示名を補完して NOT NULL 制約違反（COMMON_999/500）を防ぐ。
        String displayName = resolveDisplayName(req);
        UserEntity user = UserEntity.builder()
                .email(req.getEmail())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .lastName(req.getLastName())
                .firstName(req.getFirstName())
                .lastNameHash(encryptionService.hmac(req.getLastName()))
                .firstNameHash(encryptionService.hmac(req.getFirstName()))
                .displayName(displayName)
                .postalCode(req.getPostalCode())
                .locale(req.getLocale() != null ? req.getLocale() : "ja")
                .timezone(req.getTimezone() != null ? req.getTimezone() : "Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                .isSearchable(true)
                .birthDate(req.getBirthDate())
                .build();
        userRepository.save(user);

        // 4.5. ベータ招待トークンがあれば自動参加
        if (req.getInviteToken() != null && !req.getInviteToken().isBlank()) {
            inviteService.joinByInvite(req.getInviteToken(), user.getId());
        }

        // 5. メール認証トークン生成（SHA-256ハッシュをDB保存、平文はイベントで送信）
        String rawToken = SecureTokenGenerator.generate();
        String tokenHash = authTokenService.hashToken(rawToken);
        EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plus(EMAIL_VERIFICATION_EXPIRY))
                .build();
        emailVerificationTokenRepository.save(verificationToken);

        // 6. イベント発行（メール送信は非同期リスナーが処理）
        eventPublisher.publish(new UserRegisteredEvent(
                user.getId(), user.getEmail(), user.getLastName() + " " + user.getFirstName(), rawToken));

        // F02.10: 登録直後に郵便番号が設定されていれば地点導出を非同期でトリガーする
        // @Transactional 内で発行 → コミット後に WeatherLocationEventListener が非同期実行される
        eventPublisher.publish(new UserPostalCodeUpdatedEvent(user.getId()));

        // 7. レスポンス
        return ApiResponse.of(new MessageResponse("確認メールを送信しました"));
    }

    /**
     * 表示名（display_name）を決定する。
     *
     * <p>{@code users.display_name} は NOT NULL かつ最大 50 文字。nickname は任意入力のため、
     * 未指定（null / 空白）の場合は「姓 名」を表示名として補完する。氏名も空になることは
     * {@link RegisterRequest} の {@code @NotBlank} で防がれているため、ここでは追加で空チェックしない。
     * 50 文字を超える場合は users.display_name の長さ制約に合わせて切り詰める。</p>
     *
     * @param req 登録リクエスト
     * @return NOT NULL を満たす表示名（最大 50 文字）
     */
    private String resolveDisplayName(RegisterRequest req) {
        String nickname = req.getNickname();
        String resolved = (nickname != null && !nickname.isBlank())
                ? nickname.trim()
                : (req.getLastName() + " " + req.getFirstName()).trim();
        // users.display_name は length = 50。氏名連結が超過した場合に備えて切り詰める。
        return resolved.length() > 50 ? resolved.substring(0, 50) : resolved;
    }

    // ========================================
    // メール確認
    // ========================================

    /**
     * メール認証トークンを検証し、ユーザーを有効化する。
     *
     * @param token 平文トークン
     * @return 認証完了メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> verifyEmail(String token) {
        // 1. トークンをSHA-256ハッシュ化してDB検索
        String tokenHash = authTokenService.hashToken(token);
        EmailVerificationTokenEntity verificationToken = emailVerificationTokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));

        // 2. 期限切れ / 使用済みチェック
        if (verificationToken.getUsedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }
        if (verificationToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }

        // 3. トークンを使用済みにする
        verificationToken.markUsed();

        // 4. ユーザーを有効化（F01.9: メール認証完了時に18歳未満か判定）
        UserEntity user = userRepository.findById(verificationToken.getUserId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));
        if (user.getBirthDate() != null) {
            LocalDate parsedBirthDate = LocalDate.parse(user.getBirthDate());
            if (AgeGroupCalculator.isMinor(parsedBirthDate, LocalDate.now(TimezoneContextHolder.get()))) {
                // 18歳未満: 保護者同意待ちステータスに遷移
                user.pendingParentalConsent();
            } else {
                user.activate();
            }
        } else {
            // birth_date 未設定（移行期の旧アカウント）は ACTIVE へ
            user.activate();
        }

        // 5. イベント発行
        eventPublisher.publish(new EmailVerifiedEvent(user.getId(), user.getEmail()));

        return ApiResponse.of(new MessageResponse("メール認証が完了しました"));
    }

    /**
     * メール認証メールを再送信する。
     * クールダウン期間中は再送不可。ユーザー不在でも同一レスポンスを返す（情報漏洩防止）。
     *
     * @param email メールアドレス
     * @return 再送完了メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> resendVerificationEmail(String email) {
        // 1. Valkeyクールダウンチェック（60秒）
        String cooldownKey = EMAIL_VERIFY_COOLDOWN_PREFIX + email;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new BusinessException(AuthErrorCode.AUTH_006);
        }

        // クールダウンを設定（ユーザー有無に関わらず）
        redisTemplate.opsForValue().set(cooldownKey, "1", 60, TimeUnit.SECONDS);

        // 2. ユーザー検索（PENDING_VERIFICATION状態のみ）
        Optional<UserEntity> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty() || userOpt.get().getStatus() != UserEntity.UserStatus.PENDING_VERIFICATION) {
            // 情報漏洩防止: ユーザー不在でも同一レスポンス
            return ApiResponse.of(new MessageResponse("確認メールを送信しました"));
        }

        UserEntity user = userOpt.get();

        // 3. 新トークン生成
        String rawToken = SecureTokenGenerator.generate();
        String tokenHash = authTokenService.hashToken(rawToken);
        EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plus(EMAIL_VERIFICATION_EXPIRY))
                .build();
        emailVerificationTokenRepository.save(verificationToken);

        // 4. イベント発行
        eventPublisher.publish(new EmailVerificationResentEvent(
                user.getId(), user.getEmail(), rawToken));

        return ApiResponse.of(new MessageResponse("確認メールを送信しました"));
    }
}
