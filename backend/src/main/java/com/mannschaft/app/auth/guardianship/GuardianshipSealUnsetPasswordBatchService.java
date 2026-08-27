package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthPasswordResetService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.family.service.CareLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * F08.9 P3c-3 封印時未設定メールバッチ（02_api_design §2.3「未引き継ぎ時の保険」/ 03_security §3.1）。
 *
 * <p>封印日（{@code sealDate <= today}）になってもパスワード未設定（{@code users.password_hash} 不在）の子へ、
 * 「あなたのアカウントへようこそ。パスワードを設定してください」とパスワード設定メールを自動送付し、
 * 取り残し（自分のアカウントにログインできない子）を防ぐ。</p>
 *
 * <h3>判定</h3>
 * <ul>
 *   <li>{@code sealDate <= today}（封印日到来）。</li>
 *   <li>{@code password_hash} 未設定（{@link GuardianshipHandoverService} と同じ「パスワード設定有無」判定）。</li>
 *   <li>子のメールがルーティング可能（内部プレースホルダ {@code *.mannschaft.internal} は送付不能ゆえスキップ＋記録）。</li>
 *   <li>未送信（同一（子×境界日）1 回限り・{@link GuardianshipTransitionNotificationRepository}）。</li>
 * </ul>
 *
 * <h3>送付</h3>
 * <p>{@link AuthPasswordResetService#requestPasswordResetForSystemBatch}（F01.9 パスワードリセット基盤の
 * バッチ専用経路）を流用し F09.18 outbox 経由で送る（{@code EmailService.sendEmail} 直呼びしない）。
 * トークン生成・期限は同基盤に委譲。公開 EP 用の IP 単位レートリミットはバッチでは通さない
 * （単一実行保証ゆえ・1 分 4 件以上の取りこぼし防止）。送付が例外で失敗したら先行保存した送信記録を
 * 補償削除し、翌日のバッチで再送できるようにする。</p>
 *
 * <h3>スケジュール</h3>
 * <p>毎日 03:30 JST。{@code @SchedulerLock} で多重起動を防ぎ、{@link Clock} 注入で date-pin テスト可能。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianshipSealUnsetPasswordBatchService {

    /** ページングサイズ。 */
    static final int PAGE_SIZE = 500;

    /** 全件走査の暴走を防ぐ最大ページ数。 */
    static final int MAX_PAGES = 200;

    /** 内部用（非ルーティング）メールのドメインサフィックス。 */
    private static final String INTERNAL_EMAIL_SUFFIX = ".mannschaft.internal";

    private final ParentalConsentService parentalConsentService;
    private final CareLinkService careLinkService;
    private final UserRepository userRepository;
    private final GuardianshipAgePolicyRegistry agePolicyRegistry;
    private final GuardianshipTransitionNotificationRepository transitionNotificationRepository;
    private final AuthPasswordResetService authPasswordResetService;
    private final Clock clock;

    /**
     * 封印時未設定メールバッチ。毎日 03:30 JST に実行する。
     */
    @BatchEndpoint(name = "guardianship-seal-unset-password-batch",
            description = "自立移行 封印時の未設定パスワード自動送付（取り残し防止）バッチ")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。封印日到来後もパスワード未設定の子への設定案内であり、送付が遅れても再開後に送り直される。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "guardianshipSealUnsetPasswordBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void execute() {
        LocalDate today = LocalDate.now(clock);
        log.info("封印時未設定メールバッチ開始: today={}", today);

        // 2 経路から子ユーザーIDを重複排除して収集（保護者は問わない・子本人へ送るため）。
        Set<Long> childUserIds = new LinkedHashSet<>();
        collectChildIds(childUserIds);

        if (childUserIds.isEmpty()) {
            log.info("封印時未設定メールバッチ完了: 対象子なし");
            return;
        }

        int sentCount = 0;
        int skippedSealNotReached = 0;
        int skippedPasswordSet = 0;
        int skippedPlaceholderEmail = 0;
        int skippedAlreadySent = 0;
        int failedCount = 0;

        List<UserEntity> children = userRepository.findByIdIn(childUserIds);
        for (UserEntity child : children) {
            try {
                LocalDate sealDate = resolveSealDate(child);
                if (sealDate == null || today.isBefore(sealDate)) {
                    // 生年月日解決不能 or 封印日未到来 → 対象外。
                    skippedSealNotReached++;
                    continue;
                }
                // パスワード設定済みなら引き継ぎ完了済み → 送らない。
                if (hasPassword(child)) {
                    skippedPasswordSet++;
                    continue;
                }
                // 内部プレースホルダメールは送付不能 → スキップ＋件数を可視化（症状を隠さない）。
                if (!hasRoutableEmail(child)) {
                    log.warn("封印時未設定メール: 子 userId={} のメールが非ルーティング（プレースホルダ）のため送付不能・スキップ",
                            child.getId());
                    skippedPlaceholderEmail++;
                    continue;
                }
                // 重複送信防止（子×境界日で 1 回限り）。宛先＝子本人。
                if (transitionNotificationRepository
                        .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                                GuardianshipTransitionNotificationKind.SEAL_UNSET_PASSWORD,
                                child.getId(), child.getId(), sealDate)) {
                    skippedAlreadySent++;
                    continue;
                }

                // 送信記録を先に保存し UNIQUE 競合を検知（保存できた実行のみ送付＝二重送信を物理排除）。
                GuardianshipTransitionNotificationEntity record;
                try {
                    record = transitionNotificationRepository.save(
                            GuardianshipTransitionNotificationEntity.builder()
                                    .notificationKind(GuardianshipTransitionNotificationKind.SEAL_UNSET_PASSWORD)
                                    .recipientUserId(child.getId())
                                    .childUserId(child.getId())
                                    .sealDate(sealDate)
                                    .build());
                } catch (DuplicateKeyException dup) {
                    // 既に同一（子×境界日）で送信記録あり（並行/時刻境界の競合）→ 二重送信せずスキップ。
                    skippedAlreadySent++;
                    continue;
                }

                // パスワード設定リンク送付（F01.9 基盤・outbox 経由・バッチ専用経路でレート制限を通さない）。
                // 送付が失敗したら先行保存した記録を補償削除し、翌日リトライ可能にする
                // （@SchedulerLock で並行実行はなく補償削除は安全）。
                try {
                    authPasswordResetService.requestPasswordResetForSystemBatch(child.getEmail());
                } catch (RuntimeException sendEx) {
                    transitionNotificationRepository.delete(record);
                    failedCount++;
                    log.error("封印時未設定メール 送付失敗（記録を補償削除・翌日再送）: childUserId={}",
                            child.getId(), sendEx);
                    continue;
                }
                sentCount++;
                log.info("封印時未設定メール送付: childUserId={}, sealDate={}", child.getId(), sealDate);

            } catch (Exception e) {
                log.error("封印時未設定メール 送付失敗（継続）: childUserId={}", child.getId(), e);
                failedCount++;
            }
        }

        log.info("封印時未設定メールバッチ完了: 対象子={}件, 送付={}件, 封印未到来={}件, パスワード設定済み={}件, "
                        + "プレースホルダメール={}件, 既送信={}件, 失敗={}件",
                childUserIds.size(), sentCount, skippedSealNotReached, skippedPasswordSet,
                skippedPlaceholderEmail, skippedAlreadySent, failedCount);
    }

    /** 2 経路（parental_consent / care_links）から子ユーザーIDを重複排除して収集する。 */
    private void collectChildIds(Set<Long> childUserIds) {
        for (int page = 0; page < MAX_PAGES; page++) {
            List<ParentalConsentService.ParentChildPair> pairs =
                    parentalConsentService.listApprovedParentChildPairs(page, PAGE_SIZE);
            if (pairs.isEmpty()) {
                break;
            }
            pairs.forEach(p -> {
                if (p.childUserId() != null) {
                    childUserIds.add(p.childUserId());
                }
            });
            if (pairs.size() < PAGE_SIZE) {
                break;
            }
        }
        for (int page = 0; page < MAX_PAGES; page++) {
            List<CareLinkService.ParentChildPair> pairs =
                    careLinkService.listActiveParentWatcherPairs(page, PAGE_SIZE);
            if (pairs.isEmpty()) {
                break;
            }
            pairs.forEach(p -> {
                if (p.childUserId() != null) {
                    childUserIds.add(p.childUserId());
                }
            });
            if (pairs.size() < PAGE_SIZE) {
                break;
            }
        }
    }

    /** 子の生年月日・国コードから封印境界日を算出する。解決不能なら null。 */
    private LocalDate resolveSealDate(UserEntity child) {
        LocalDate birthDate = parseBirthDate(child);
        if (birthDate == null) {
            log.warn("封印時未設定メール: 子 userId={} の birthDate 解決不能のためスキップ（安全側）", child.getId());
            return null;
        }
        GuardianshipAgePolicy policy = agePolicyRegistry.forCountry(child.getCountryCode());
        return policy.sealDate(birthDate, clock);
    }

    /** パスワード設定有無（GuardianshipHandoverService / GuardianshipSwitchService と同一判定）。 */
    private boolean hasPassword(UserEntity child) {
        return child.getPasswordHash() != null && !child.getPasswordHash().isBlank();
    }

    /** ルーティング可能（実）メールを持つか。内部プレースホルダ（*.mannschaft.internal）は「なし」とみなす。 */
    private boolean hasRoutableEmail(UserEntity child) {
        String email = child.getEmail();
        if (email == null || email.isBlank()) {
            return false;
        }
        return !email.toLowerCase(Locale.ROOT).endsWith(INTERNAL_EMAIL_SUFFIX);
    }

    /** 暗号化 birthDate（復号済み ISO-8601 文字列）を LocalDate にパース。不正なら null。 */
    private LocalDate parseBirthDate(UserEntity child) {
        String raw = child.getBirthDate();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("封印時未設定メール: 子 userId={} の birthDate パース失敗（不正フォーマット）", child.getId());
            return null;
        }
    }
}
