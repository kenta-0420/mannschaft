package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F08.9 P3c-3 進学予告バッチ（02_api_design §2.3 / 03_security §3.1）。
 *
 * <p>子が後見切替の封印境界日（{@link GuardianshipAgePolicy#sealDate}）の <b>3ヶ月前</b>に入ったら、
 * 保護者へ「◯月からお子さまが自立します。ログイン情報の引き継ぎをお願いします」と事前通知する。
 * 通知チャネルはアプリ内通知（F04.11 統合インボックスに載る・{@link NotificationHelper} 正準経路）と
 * メール（F09.18 outbox 経由）。</p>
 *
 * <h3>対象抽出</h3>
 * <ol>
 *   <li>parental_consent_links（APPROVED）と user_care_links（ACTIVE PARENT）の全 (保護者, 子) ペアを
 *       ページングで横断列挙（N+1 と全件メモリ展開を避ける）。</li>
 *   <li>子の生年月日・国コードから {@code sealDate} を算出（バッチなので都度復号可）。</li>
 *   <li>{@code today ∈ [sealDate.minusMonths(3), sealDate)} かつ未送信のものだけ送る。</li>
 * </ol>
 *
 * <h3>重複送信防止</h3>
 * <p>同一（保護者×子×境界日）で 1 回限り。送信前に
 * {@link GuardianshipTransitionNotificationRepository} で既送信を確認し、送信後にレコードを保存する。
 * UNIQUE 制約が二重防御（並行・時刻境界の競合を DB が弾く）。</p>
 *
 * <h3>スケジュール</h3>
 * <p>毎日 03:00 JST。{@code @SchedulerLock} で多重起動を防ぎ、{@link Clock} 注入で date-pin テスト可能。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianshipProgressionNoticeBatchService {

    /** ページングサイズ（1 ページあたりの (保護者,子) ペア取得件数）。 */
    static final int PAGE_SIZE = 500;

    /** 全件走査の暴走を防ぐ最大ページ数（500 件 × 200 ページ = 10 万ペア／回）。 */
    static final int MAX_PAGES = 200;

    /** 事前通知を開始する境界日からの遡及月数（3ヶ月前）。 */
    static final int NOTICE_LEAD_MONTHS = 3;

    /** メール outbox の sourceDomain（auth ドメイン由来）。 */
    private static final String SOURCE_DOMAIN = "auth";

    /** メール outbox のテンプレート種別（スルー方式・subject/body を呼び出し側で組み立て）。 */
    private static final String EMAIL_TEMPLATE_KIND = "GUARDIANSHIP_PROGRESSION_NOTICE";

    /** アプリ内通知の通知種別。 */
    private static final String NOTIFICATION_TYPE = "GUARDIANSHIP_PROGRESSION_NOTICE";

    /** アプリ内通知の sourceType（F00 visibility マッパー非対応＝fail-soft で素通り）。 */
    private static final String NOTIFICATION_SOURCE_TYPE = "GUARDIANSHIP_PROGRESSION";

    private final ParentalConsentService parentalConsentService;
    private final CareLinkService careLinkService;
    private final UserRepository userRepository;
    private final GuardianshipAgePolicyRegistry agePolicyRegistry;
    private final GuardianshipTransitionNotificationRepository transitionNotificationRepository;
    private final NotificationHelper notificationHelper;
    private final EmailOutboxService emailOutboxService;
    private final MessageSource messageSource;
    private final Clock clock;

    /**
     * 進学予告（3ヶ月前事前通知）バッチ。毎日 03:00 JST に実行する。
     */
    @BatchEndpoint(name = "guardianship-progression-notice-batch",
            description = "自立移行 進学予告（封印3ヶ月前・保護者へ事前通知）バッチ")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。保護者同意の段階進行の事前通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "guardianshipProgressionNoticeBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void execute() {
        LocalDate today = LocalDate.now(clock);
        log.info("進学予告バッチ開始: today={}", today);

        // (保護者, 子) ペアを 2 経路から重複排除しつつ走査。ペアは「保護者IDと子IDの組」で一意化する。
        Set<String> pairKeys = new LinkedHashSet<>();
        // 子の属性をまとめてロードするため、子IDごとに保護者IDの集合を持つ（N+1 防止）。
        Map<Long, Set<Long>> guardiansByChild = new LinkedHashMap<>();

        boolean truncated = collectPairs(pairKeys, guardiansByChild, today);

        if (guardiansByChild.isEmpty()) {
            log.info("進学予告バッチ完了: 対象ペアなし（打ち切り={}）", truncated);
            return;
        }

        int sentCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        // 子の属性を一括ロード（N+1 防止）。
        List<UserEntity> childUsers = userRepository.findByIdIn(guardiansByChild.keySet());
        Map<Long, UserEntity> childById = new LinkedHashMap<>();
        for (UserEntity child : childUsers) {
            childById.put(child.getId(), child);
        }

        for (Map.Entry<Long, Set<Long>> entry : guardiansByChild.entrySet()) {
            Long childUserId = entry.getKey();
            UserEntity child = childById.get(childUserId);
            if (child == null) {
                // リンクはあるが子が存在しない／論理削除済み → スキップ（記録で症状を隠さない）。
                log.debug("進学予告: 子ユーザー不在のためスキップ childUserId={}", childUserId);
                skippedCount += entry.getValue().size();
                continue;
            }

            LocalDate sealDate = resolveSealDate(child);
            if (sealDate == null) {
                // 生年月日解決不能 → 境界日を算出できないためスキップ（安全側・記録あり）。
                skippedCount += entry.getValue().size();
                continue;
            }

            // today が [sealDate-3ヶ月, sealDate) の予告ウィンドウに入っていなければ対象外。
            if (!isInNoticeWindow(today, sealDate)) {
                skippedCount += entry.getValue().size();
                continue;
            }

            for (Long guardianUserId : entry.getValue()) {
                try {
                    boolean sent = notifyGuardian(guardianUserId, child, sealDate);
                    if (sent) {
                        sentCount++;
                    } else {
                        skippedCount++;
                    }
                } catch (Exception e) {
                    log.error("進学予告 送信失敗（継続）: guardianUserId={}, childUserId={}",
                            guardianUserId, childUserId, e);
                    failedCount++;
                }
            }
        }

        log.info("進学予告バッチ完了: 対象子={}件, 送信={}件, スキップ={}件, 失敗={}件, 打ち切り={}",
                guardiansByChild.size(), sentCount, skippedCount, failedCount, truncated);
    }

    /**
     * 2 経路（parental_consent / care_links）から (保護者, 子) ペアをページングで収集する。
     * {@code pairKeys} で (保護者,子) の重複を排除しつつ、{@code guardiansByChild} に子→保護者集合を蓄積する。
     *
     * <p>各経路は {@link #MAX_PAGES} 件のページを走査しても終端（空ページ、または
     * {@link #PAGE_SIZE} 未満のページ）に到達しなければ、そこで打ち切って WARN ログを残す
     * （無言の切り捨て禁止。対処療法禁止の原則に基づき、根本解決＝キーセットページング化は別任務とする）。
     *
     * @return いずれかの経路が {@link #MAX_PAGES} で打ち切られ、収集しきれなかった場合 true
     */
    private boolean collectPairs(Set<String> pairKeys, Map<Long, Set<Long>> guardiansByChild, LocalDate today) {
        boolean consentTruncated = collectParentalConsentPairs(pairKeys, guardiansByChild);
        boolean careLinkTruncated = collectCareLinkPairs(pairKeys, guardiansByChild);
        return consentTruncated || careLinkTruncated;
    }

    /** parental_consent_links（APPROVED）経路のページング収集。打ち切られたら true。 */
    private boolean collectParentalConsentPairs(Set<String> pairKeys, Map<Long, Set<Long>> guardiansByChild) {
        for (int page = 0; page < MAX_PAGES; page++) {
            List<ParentalConsentService.ParentChildPair> pairs =
                    parentalConsentService.listApprovedParentChildPairs(page, PAGE_SIZE);
            if (pairs.isEmpty()) {
                return false;
            }
            for (ParentalConsentService.ParentChildPair pair : pairs) {
                addPair(pairKeys, guardiansByChild, pair.parentUserId(), pair.childUserId());
            }
            if (pairs.size() < PAGE_SIZE) {
                return false;
            }
        }
        logCollectionTruncated("parental_consent_links");
        return true;
    }

    /** user_care_links（ACTIVE PARENT）経路のページング収集。打ち切られたら true。 */
    private boolean collectCareLinkPairs(Set<String> pairKeys, Map<Long, Set<Long>> guardiansByChild) {
        for (int page = 0; page < MAX_PAGES; page++) {
            List<CareLinkService.ParentChildPair> pairs =
                    careLinkService.listActiveParentWatcherPairs(page, PAGE_SIZE);
            if (pairs.isEmpty()) {
                return false;
            }
            for (CareLinkService.ParentChildPair pair : pairs) {
                addPair(pairKeys, guardiansByChild, pair.parentUserId(), pair.childUserId());
            }
            if (pairs.size() < PAGE_SIZE) {
                return false;
            }
        }
        logCollectionTruncated("user_care_links");
        return true;
    }

    /** ページング走査が MAX_PAGES で打ち切られた際の WARN ログ（無言の切り捨て禁止）。 */
    private void logCollectionTruncated(String sourceName) {
        log.warn("進学予告: {} のペア収集がページ上限（{}件 = {}件×{}ページ）に到達したため打ち切り。"
                        + "上限を超えた分は今回の対象から漏れており、該当保護者には通知が届かない状態。",
                sourceName, PAGE_SIZE * MAX_PAGES, PAGE_SIZE, MAX_PAGES);
    }

    /** (保護者, 子) を重複排除して蓄積する。自分自身が子のペアは防御的に除外。 */
    private void addPair(Set<String> pairKeys, Map<Long, Set<Long>> guardiansByChild,
                         Long guardianUserId, Long childUserId) {
        if (guardianUserId == null || childUserId == null || guardianUserId.equals(childUserId)) {
            return;
        }
        // (保護者,子) の重複排除キー。文字列キーにすることで数学的なハッシュ衝突を構造的に排除する。
        String key = guardianUserId + ":" + childUserId;
        if (!pairKeys.add(key)) {
            return;
        }
        guardiansByChild.computeIfAbsent(childUserId, k -> new LinkedHashSet<>()).add(guardianUserId);
    }

    /**
     * 1 件の保護者へ進学予告を送る（既送信ならスキップ）。
     *
     * @return 実際に送信したら true、既送信スキップなら false
     */
    private boolean notifyGuardian(Long guardianUserId, UserEntity child, LocalDate sealDate) {
        // 重複送信防止: 既に (PROGRESSION_NOTICE, 保護者, 子, 境界日) で送信済みなら何もしない。
        if (transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        GuardianshipTransitionNotificationKind.PROGRESSION_NOTICE,
                        guardianUserId, child.getId(), sealDate)) {
            return false;
        }

        UserEntity guardian = userRepository.findById(guardianUserId).orElse(null);
        if (guardian == null) {
            log.debug("進学予告: 保護者ユーザー不在のためスキップ guardianUserId={}", guardianUserId);
            return false;
        }

        Locale locale = resolveLocale(guardian);
        String childName = displayNameOf(child);
        int sealMonth = sealDate.getMonthValue();

        String title = messageSource.getMessage(
                "notification.guardianship.progression.title", null,
                "お子さまの自立が近づいています", locale);
        String body = messageSource.getMessage(
                "notification.guardianship.progression.body",
                new Object[]{childName, sealMonth},
                childName + "さんは" + sealMonth + "月から自立します。ログイン情報の引き継ぎをお願いします。",
                locale);

        // 送信記録を先に保存し、UNIQUE 競合（並行/時刻境界）を確実に検知する。
        // 保存に成功した実行のみが送信する（二重送信を物理的に排除）。
        try {
            transitionNotificationRepository.save(GuardianshipTransitionNotificationEntity.builder()
                    .notificationKind(GuardianshipTransitionNotificationKind.PROGRESSION_NOTICE)
                    .recipientUserId(guardianUserId)
                    .childUserId(child.getId())
                    .sealDate(sealDate)
                    .build());
        } catch (DuplicateKeyException dup) {
            // 別実行が直前に送信済み（UNIQUE 1062）。二重送信せずスキップ。
            // FK 違反等の他の整合性違反はここでは握らず、呼び出し元の汎用 catch で失敗カウントに流す。
            log.debug("進学予告: 送信記録の重複検知（並行実行）guardianUserId={}, childUserId={}",
                    guardianUserId, child.getId());
            return false;
        }

        // アプリ内通知（F04.11 統合インボックスに載る正準経路）。
        notificationHelper.notify(
                guardianUserId,
                NOTIFICATION_TYPE,
                title,
                body,
                NOTIFICATION_SOURCE_TYPE,
                child.getId(),
                NotificationScopeType.PERSONAL,
                guardianUserId,
                "/me/guardianship/children/" + child.getId() + "/independence",
                null);

        // メール（ルーティング可能な保護者メールがある場合のみ・outbox 経由）。
        sendEmailIfPossible(guardian, locale, title, body, child.getId(), sealDate);

        return true;
    }

    /** 保護者にルーティング可能なメールがあれば outbox に enqueue する（subject/body スルー方式）。 */
    private void sendEmailIfPossible(UserEntity guardian, Locale locale,
                                     String subject, String body, Long childUserId, LocalDate sealDate) {
        String email = guardian.getEmail();
        if (email == null || email.isBlank() || email.toLowerCase(Locale.ROOT).endsWith(".mannschaft.internal")) {
            log.debug("進学予告: 保護者メールが非ルーティングのためメール送付スキップ guardianUserId={}", guardian.getId());
            return;
        }
        try {
            emailOutboxService.enqueue(new EmailOutboxRequest(
                    EMAIL_TEMPLATE_KIND,
                    locale.toLanguageTag(),
                    email,
                    Map.of("subject", subject, "body", body),
                    SOURCE_DOMAIN,
                    "guardianship-progression:" + guardian.getId() + ":" + childUserId + ":" + sealDate,
                    null,
                    guardian.getId(),
                    null));
        } catch (RuntimeException e) {
            // メールは補助チャネル。失敗してもアプリ内通知＋送信記録は確定済みのため握って継続。
            log.warn("進学予告: メール enqueue 失敗（継続）guardianUserId={}, error={}",
                    guardian.getId(), e.getMessage());
        }
    }

    /** today が予告ウィンドウ [sealDate-3ヶ月, sealDate) に入っているか。封印当日以降は対象外。 */
    private boolean isInNoticeWindow(LocalDate today, LocalDate sealDate) {
        LocalDate windowStart = sealDate.minusMonths(NOTICE_LEAD_MONTHS);
        // [windowStart, sealDate) ＝ windowStart 以降かつ sealDate より前。
        return !today.isBefore(windowStart) && today.isBefore(sealDate);
    }

    /** 子の生年月日・国コードから封印境界日を算出する。解決不能なら null。 */
    private LocalDate resolveSealDate(UserEntity child) {
        LocalDate birthDate = parseBirthDate(child);
        if (birthDate == null) {
            log.warn("進学予告: 子 userId={} の birthDate 解決不能のためスキップ（安全側）", child.getId());
            return null;
        }
        GuardianshipAgePolicy policy = agePolicyRegistry.forCountry(child.getCountryCode());
        return policy.sealDate(birthDate, clock);
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
            log.warn("進学予告: 子 userId={} の birthDate パース失敗（不正フォーマット）", child.getId());
            return null;
        }
    }

    /** ユーザーのロケールを解決（未設定は ja）。 */
    private Locale resolveLocale(UserEntity user) {
        String locale = user.getLocale();
        return (locale == null || locale.isBlank()) ? Locale.JAPANESE : Locale.forLanguageTag(locale);
    }

    /** 表示名を解決（displayName 優先・なければ姓名）。 */
    private String displayNameOf(UserEntity user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        String last = user.getLastName() != null ? user.getLastName() : "";
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String name = (last + " " + first).trim();
        return name.isEmpty() ? "お子さま" : name;
    }
}
