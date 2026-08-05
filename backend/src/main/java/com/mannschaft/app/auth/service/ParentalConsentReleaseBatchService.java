package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.util.AgeGroupCalculator;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F01.9 年齢確認・保護者同意機能: 18歳到達保護者同意自動解放バッチ。
 *
 * <p>毎日 02:00 JST に実行し、APPROVED リンクを持つ子ユーザーの誕生日を確認する。
 * 18歳以上（成人）に達している場合、全 APPROVED リンクを REVOKED に更新し、
 * 子ユーザーへ通知メールを送信する。</p>
 *
 * <h2>成人判定を二段構えにする理由</h2>
 * <p>{@code users.birth_date} は AES-256-GCM 暗号化列（ランダム IV）であり、SQL 上の比較は
 * 暗号文同士のバイト比較にしかならず日付順とは無関係である。よって年齢条件をそのまま
 * {@code WHERE} 句に書くことはできない。平文で比較可能なのは索引付きの
 * {@code users.birth_year} のみであるため、</p>
 * <ol>
 *   <li>SQL で {@code birth_year <= 今年 − 18} により<b>成人を取りこぼさない</b>候補を粗く取得し、</li>
 *   <li>取得した各リンクの子ユーザーについて、復号済み {@code birthDate} を
 *       {@link AgeGroupCalculator} に掛けて<b>境界年の未成年を確定的に除外</b>する</li>
 * </ol>
 * <p>という二段構えにする。年齢判定ロジックは {@link AgeGroupCalculator} が唯一の算出元であり、
 * 本クラスで独自に書き起こしてはならない。</p>
 *
 * <h2>飢餓が起きない理由</h2>
 * <p>取得は {@code id} 昇順のキーセットページングで行い、<b>解放したかどうかにかかわらず
 * カーソルを検査済みの最後の {@code id} まで必ず前進させる</b>。したがって先頭ページが
 * 境界年の未成年で埋まっても次の周回で必ず先へ進み、後方の成人到達者へ到達できる。
 * 先頭ページを毎回取り直すオフセットページングに戻すと、事後フィルタと組み合わさった瞬間に
 * 成人到達者が未成年に埋もれて永久に解放されない飢餓が発生する（未成年保護の
 * 法的要件に直結する）。</p>
 *
 * <p>ページングサイズ: 500件。個別ユーザーの処理失敗は継続する（1ユーザーの失敗で全体停止しない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParentalConsentReleaseBatchService {

    /** ページングサイズ（1バッチあたりの取得件数）*/
    private static final int PAGE_SIZE = 500;

    /**
     * 日付判定の基準タイムゾーン。
     *
     * <p>{@code @Scheduled(zone = "Asia/Tokyo")} と同一に固定する。バッチスレッドは
     * {@code UserTimezoneFilter} を通らないため {@code TimezoneContextHolder.get()} は
     * 既定の UTC を返す（同クラスの Javadoc に明記のとおり）。JVM 既定 TZ も運用環境では
     * UTC になり得る。いずれの場合も「今日」が JST とずれ、18歳到達日の判定が
     * 丸一日前後する。ユーザー文脈を持たないバッチではサービス基準時刻である JST に固定する。</p>
     */
    private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Tokyo");

    /** キーセットページングの初期カーソル（全 UUID の下限）。*/
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final ParentalConsentLinkRepository parentalConsentLinkRepository;
    private final UserRepository userRepository;
    private final EmailOutboxService emailOutboxService;

    /**
     * 18歳到達した子ユーザーの保護者同意リンクを自動解放するバッチ処理。
     * 毎日 02:00 JST に実行する。
     */
    @BatchEndpoint(name = "parental-consent-release-batch", description = "18歳到達保護者同意自動解放バッチ")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "parentalConsentReleaseBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    @Transactional
    public void execute() {
        log.info("18歳到達保護者同意自動解放バッチ開始");

        LocalDate today = LocalDate.now(BATCH_ZONE);
        // 成人判定の閾値は AgeGroupCalculator が唯一の算出元（年齢判定を二重実装しない）。
        // その「生年」を SQL の粗い絞り込みに使う。閾値以前に生まれた者の生年は必ずこの年以下なので、
        // 成人を取りこぼすことはない（境界年に混ざる未成年は後段の復号判定で除外する）。
        LocalDate adultBirthDateThreshold = AgeGroupCalculator.adultBirthDateThreshold(today);
        int maxBirthYear = adultBirthDateThreshold.getYear();

        int successCount = 0;
        int failedCount = 0;
        int skippedMinorCount = 0;
        int releasedLinkCount = 0;
        int pageCount = 0;
        UUID cursor = MIN_UUID;

        while (true) {
            List<ParentalConsentLinkEntity> candidates = parentalConsentLinkRepository
                    .findAdultCandidateLinksAfterId(ParentalConsentLinkStatus.APPROVED,
                            maxBirthYear, cursor, PageRequest.of(0, PAGE_SIZE));

            if (candidates.isEmpty()) {
                break;
            }
            pageCount++;

            // カーソルは処理結果にかかわらず必ず前進させる。
            // これにより「先頭ページが境界年の未成年で埋まる」状況でも次周で先へ進み、飢餓が起こり得ない。
            cursor = candidates.get(candidates.size() - 1).getId();

            // child_user_id で distinct にグループ化（1ユーザーへの通知は1通）。
            // 取得順（id 昇順）を保つため LinkedHashMap でまとめる。
            Map<Long, List<ParentalConsentLinkEntity>> linksByChildUserId = candidates.stream()
                    .collect(Collectors.groupingBy(ParentalConsentLinkEntity::getChildUserId,
                            LinkedHashMap::new, Collectors.toList()));

            for (Map.Entry<Long, List<ParentalConsentLinkEntity>> entry : linksByChildUserId.entrySet()) {
                Long childUserId = entry.getKey();
                List<ParentalConsentLinkEntity> childLinks = entry.getValue();

                try {
                    Optional<UserEntity> userOpt = userRepository.findById(childUserId);
                    if (userOpt.isEmpty()) {
                        log.warn("取得済みリンクの子ユーザーが参照できないためスキップ: childUserId={}", childUserId);
                        continue;
                    }
                    UserEntity user = userOpt.get();

                    // 成人判定の確定: 復号済み birthDate を AgeGroupCalculator に掛ける。
                    // SQL 側は生年での粗い絞り込みでしかないため、ここを省くと境界年の未成年を解放してしまう。
                    LocalDate birthDate = parseBirthDate(user);
                    if (birthDate == null || AgeGroupCalculator.isMinor(birthDate, today)) {
                        // 生年月日が解決できない場合も解放しない（安全側）。
                        skippedMinorCount++;
                        continue;
                    }

                    // 成人到達済み: 対象の APPROVED リンクを全て REVOKED に更新
                    int revokedForChild = 0;
                    for (ParentalConsentLinkEntity link : childLinks) {
                        if (link.getStatus() == ParentalConsentLinkStatus.APPROVED) {
                            link.revoke(null); // revokedBy = null = SYSTEM による自動解放
                            revokedForChild++;
                        }
                    }
                    releasedLinkCount += revokedForChild;

                    // 子ユーザーへ通知メール送信
                    String displayName = user.getDisplayName() != null ? user.getDisplayName()
                            : user.getLastName() + " " + user.getFirstName();
                    emailOutboxService.enqueue(new EmailOutboxRequest(
                            "PARENTAL_CONSENT_RELEASED",
                            user.getLocale() != null ? user.getLocale() : "ja",
                            user.getEmail(),
                            Map.of("displayName", displayName),
                            "auth",
                            null,
                            null,
                            user.getId(),
                            null
                    ));

                    log.info("保護者同意自動解放完了: childUserId={}, 解放リンク数={}", childUserId, revokedForChild);
                    successCount++;

                } catch (Exception e) {
                    log.error("保護者同意自動解放失敗: childUserId={}", childUserId, e);
                    failedCount++;
                }
            }

            if (candidates.size() < PAGE_SIZE) {
                break;
            }
        }

        log.info("18歳到達保護者同意自動解放バッチ完了: 取得ページ={}, 解放ユーザー={}人, 解放リンク={}件, "
                        + "未到達スキップ={}人, 失敗={}人",
                pageCount, successCount, releasedLinkCount, skippedMinorCount, failedCount);
    }

    /**
     * 復号済み {@code birthDate}（ISO-8601 文字列）を {@link LocalDate} にパースする。
     *
     * @param user 子ユーザー
     * @return パース結果。未設定または不正フォーマットの場合は {@code null}
     */
    private LocalDate parseBirthDate(UserEntity user) {
        String raw = user.getBirthDate();
        if (raw == null || raw.isBlank()) {
            log.warn("子ユーザーの生年月日が未設定のため解放しない（安全側）: childUserId={}", user.getId());
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            log.warn("子ユーザーの生年月日をパースできないため解放しない（安全側）: childUserId={}", user.getId());
            return null;
        }
    }
}
