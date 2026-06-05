package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.AuthPasswordResetService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F08.9 P3c-2 自立移行の引き継ぎサービス（02_api_design §2.3 / 03_security §3.2）。
 *
 * <p>中学進学等で後見切替が封印される前後に、子が自分のアカウントへログインできなくなる事故を防ぐため、
 * 保護者が子のメール宛に<b>パスワード設定リンク</b>を送る引き継ぎフローを担う。
 * メール送付は F01.9 のパスワードリセット基盤（{@link AuthPasswordResetService}）を流用し、
 * F09.18 メール配信 outbox（{@code PasswordResetRequestedEvent} → {@code EmailOutboxService.enqueue}）経由で送る
 * （{@code EmailService.sendEmail} 直呼びはしない）。</p>
 *
 * <h3>認可・安全境界</h3>
 * <ul>
 *   <li><b>有効な保護者のみ</b>: parental_consent（APPROVED）または care_links（ACTIVE PARENT）。
 *       他人の子は {@link MembershipBillingErrorCode#GUARDIANSHIP_LINK_NOT_FOUND}（403・IDOR 防止）。</li>
 *   <li><b>acting-as 中は拒否</b>: 本操作は保護者本人の権原で行う引き継ぎ操作であり、後見切替セッション
 *       （{@code X-Proxy-For-User-Id} 付き）中に呼ぶものではない。子の認証情報に関わるため
 *       {@link AuthenticationCriticalOperationGuard#assertNotActingAs()} を適用する（03_security §3.2 の精神）。</li>
 *   <li><b>レート制限・トークン期限</b>: メール送付の濫用防止は {@link AuthPasswordResetService#requestPasswordReset}
 *       内部のレート制限に委ねる。リクエスト元 IP 単位で Valkey（{@code mannschaft:auth:password_reset_attempt:<ip>}）に
 *       スライディングウィンドウ（1 分間 3 回まで）を持ち、超過時は例外で 429 相当を返す。
 *       発行されるパスワード設定リンクのトークンは 30 分で失効する。
 *       本サービスは独自のレート制限・トークン管理を持たず、この基盤に一本化する。</li>
 * </ul>
 *
 * <h3>子メールの解決ルール（02_api_design §2.3）</h3>
 * <ul>
 *   <li>子に既存（ルーティング可能な）メールあり ＋ {@code childEmail} 指定 → 400
 *       （既存メールの上書きはメール変更フローの迂回ゆえ拒否）。</li>
 *   <li>子に既存メールあり ＋ {@code childEmail} なし → 既存メールへパスワード設定リンク送付。</li>
 *   <li>子にメールなし ＋ {@code childEmail} 指定 → 重複チェック後 {@code users.email} へ登録してから送付。</li>
 *   <li>子にメールなし ＋ {@code childEmail} なし → 400（{@code GUARDIANSHIP_HANDOVER_EMAIL_REQUIRED}）。</li>
 * </ul>
 *
 * <p>{@code users.email} は NOT NULL UNIQUE のため、新規登録された子も何らかのメールを持つ。
 * 「メールなし」は内部用プレースホルダ（{@code *.mannschaft.internal} ＝退会匿名化等の非ルーティング値）の場合を指す。
 * 通常の子は実メールを持つため {@code childEmail} 指定は 400 となる（設計意図どおり）。</p>
 *
 * <p><b>第三波（未実装）</b>: 3ヶ月前事前通知バッチ・封印時の未設定メール自動送付バッチは本波（P3c-2）の対象外。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GuardianshipHandoverService {

    /** 内部用（非ルーティング）メールのドメインサフィックス。退会匿名化・管理アカウント等のプレースホルダ。 */
    private static final String INTERNAL_EMAIL_SUFFIX = ".mannschaft.internal";

    private final ParentalConsentService parentalConsentService;
    private final CareLinkService careLinkService;
    private final UserRepository userRepository;
    private final AuthPasswordResetService authPasswordResetService;
    private final AuthenticationCriticalOperationGuard authenticationCriticalOperationGuard;
    private final AuditLogService auditLogService;

    /**
     * 自立移行の引き継ぎを開始する（子のメールへパスワード設定リンクを送付）。
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザーID
     * @param childUserId    対象の子のユーザーID（パスから取得・IDOR 防止）
     * @param childEmail     子のメール（子がメール未登録の場合のみ指定可・任意）
     * @param ipAddress      リクエスト元 IP（パスワードリセットのレートリミットに使用）
     * @throws BusinessException リンクなし（403）/ acting-as 中（403）/ メール解決不能・上書き要求（400）/ メール重複（400）
     */
    @Transactional
    public void initiateHandover(Long guardianUserId, Long childUserId, String childEmail, String ipAddress) {
        // 1. acting-as（後見切替セッション）中は拒否。本操作は保護者本人の権原で行う。
        authenticationCriticalOperationGuard.assertNotActingAs();

        // 2. 有効な保護者リンク検証（IDOR 防止）。リンクなし／他人の子は 403。
        boolean linked = guardianUserId != null && childUserId != null
                && (parentalConsentService.isApprovedGuardian(guardianUserId, childUserId)
                || careLinkService.isActiveParentWatcher(guardianUserId, childUserId));
        if (!linked) {
            log.warn("引き継ぎ開始拒否: 有効な保護者リンクなし guardianUserId={}, childUserId={}",
                    guardianUserId, childUserId);
            throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
        }

        UserEntity child = userRepository.findById(childUserId).orElse(null);
        if (child == null) {
            // リンクはあるが子が存在しない不整合 → 情報を漏らさず 403。
            log.warn("引き継ぎ開始拒否: 子ユーザー不在 childUserId={}", childUserId);
            throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
        }

        boolean hasUsableEmail = hasRoutableEmail(child);
        String requestedEmail = normalizeOrNull(childEmail);

        String targetEmail;
        boolean registeredNewEmail = false;
        if (hasUsableEmail) {
            // 子に既存メールあり: childEmail 指定は上書き要求（メール変更フローの迂回）ゆえ拒否。
            if (requestedEmail != null) {
                log.warn("引き継ぎ開始拒否: 既存メールがあるのに childEmail が指定された childUserId={}", childUserId);
                throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_HANDOVER_EMAIL_REQUIRED);
            }
            targetEmail = child.getEmail();
        } else {
            // 子にメールなし: childEmail 未指定なら 400、指定ありなら重複チェック後に登録。
            if (requestedEmail == null) {
                log.warn("引き継ぎ開始拒否: 子にメールがなく childEmail も未指定 childUserId={}", childUserId);
                throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_HANDOVER_EMAIL_REQUIRED);
            }
            if (userRepository.existsByEmail(requestedEmail)) {
                throw new BusinessException(AuthErrorCode.AUTH_013);
            }
            // users.email へ登録（既存のメール登録パターンに従い toBuilder で差し替え保存）。
            userRepository.save(child.toBuilder().email(requestedEmail).build());
            targetEmail = requestedEmail;
            registeredNewEmail = true;
        }

        // 3. パスワード設定リンク送付（F01.9 パスワードリセット基盤を流用・outbox 経由で enqueue）。
        //    ユーザー不在でも同一レスポンスを返す実装だが、ここでは対象メールを確実に解決済みのため必ず送付される。
        authPasswordResetService.requestPasswordReset(targetEmail, ipAddress);

        // 4. 監査記録（audit_logs・センシティブ）。childEmail を新規登録したかを metadata に残す。
        String metadata = "{\"childUserId\":" + childUserId
                + ",\"registeredNewEmail\":" + registeredNewEmail + "}";
        auditLogService.record(
                AuditEventType.GUARDIANSHIP_HANDOVER_INITIATED.name(),
                guardianUserId,   // userId: 操作者＝保護者
                childUserId,      // targetUserId: 対象＝子
                null, null,       // teamId / organizationId
                null, null,       // ipAddress / userAgent（Service 層からは取得しない）
                null,             // sessionHash
                metadata);
        log.info("自立移行の引き継ぎ開始: guardianUserId={} → childUserId={} (registeredNewEmail={})",
                guardianUserId, childUserId, registeredNewEmail);
    }

    /**
     * 子がルーティング可能（実）メールを持つかを判定する。
     * 内部用プレースホルダ（{@code *.mannschaft.internal}）は「メールなし」とみなす。
     */
    private boolean hasRoutableEmail(UserEntity child) {
        String email = child.getEmail();
        if (email == null || email.isBlank()) {
            return false;
        }
        return !email.toLowerCase(java.util.Locale.ROOT).endsWith(INTERNAL_EMAIL_SUFFIX);
    }

    /** 入力メールを trim し、空文字／null は null に正規化する。 */
    private String normalizeOrNull(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
