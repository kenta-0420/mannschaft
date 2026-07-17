package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 村ニュースレター号サービス（F17.1 ②-2・設計書 §4.2 / §5）。
 *
 * <p>集計器（{@link VillageNewsletterDigestAggregator}）で確定した snapshot を号エンティティへ複写し、
 * 「集計 → 凍結」を 1 トランザクションで行う。凍結後のダイジェストは不変（改ざん不可・要件①）。</p>
 *
 * <h2>改ざん不可の担保（AC-02・設計書 §4.2）</h2>
 * <ul>
 *   <li>号エンティティ {@link VillageNewsletterIssueEntity} は {@code digest_*} に setter を一切持たない
 *       （更新経路が存在しない）。値は生成時に {@code @SuperBuilder} 経由でのみ確定する。</li>
 *   <li>{@link VillageNewsletterIssueEntity#freeze} は {@code AGGREGATED} 以外からの遷移を
 *       {@link IllegalStateException} で拒否する。本サービスはこれを
 *       {@link VillageErrorCode#NEWSLETTER_ISSUE_ALREADY_FROZEN} に翻訳し、
 *       凍結済み号の再集計・再凍結を型付きドメインエラーとして弾く。</li>
 * </ul>
 *
 * <h2>原則準拠</h2>
 * <p>原則5: 集計・凍結・監査は village ドメイン内で完結する。配信（notification 越境）は ②-3 で分離。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageNewsletterIssueService {

    private final VillageNewsletterIssueRepository issueRepository;
    private final VillageNewsletterDigestAggregator aggregator;
    private final AuditLogService auditLogService;

    /**
     * 指定村・指定期間の号を集計し凍結する（AGGREGATED → FROZEN）。
     *
     * <p><b>冪等（AC-03）</b>: 同一村×頻度×{@code periodStart} の号が既に存在する場合は、
     * 集計も保存も行わず既存号をそのまま返す（集計バッチの二重起動・再走に対して安全）。
     * 既存号の凍結ダイジェストは触らない＝改ざん不可（AC-02）。</p>
     *
     * @param villageId          村 ID
     * @param frequency          頻度（WEEKLY / MONTHLY）
     * @param newsletterId       紐づくニュースレター設定 ID（号外では null）
     * @param periodStart        集計期間の開始（含む）
     * @param periodEnd          集計期間の終了（含まない・集計基準時刻）
     * @param scheduledPublishAt 配信予定時刻（ラグの終端）
     * @return 生成・凍結した号（既存があればその号）
     * @throws BusinessException 既存号が凍結済みで再集計を試みた場合（{@link VillageErrorCode#NEWSLETTER_ISSUE_ALREADY_FROZEN}）
     */
    @Transactional
    public VillageNewsletterIssueEntity aggregateAndFreeze(
            UUID villageId,
            VillageNewsletterFrequency frequency,
            UUID newsletterId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            LocalDateTime scheduledPublishAt) {

        // 冪等（AC-03）: 既存号があれば何もせず返す。凍結済み snapshot は不変（AC-02）。
        Optional<VillageNewsletterIssueEntity> existing = issueRepository
                .findByVillageIdAndFrequencyAndPeriodStart(villageId, frequency, periodStart);
        if (existing.isPresent()) {
            log.debug("ニュースレター号は既に存在するため再集計しない（冪等）: villageId={} frequency={} periodStart={}",
                    villageId, frequency, periodStart);
            return existing.get();
        }

        NewsletterDigestSnapshot snapshot = aggregator.aggregate(villageId, periodStart, periodEnd);
        List<Map.Entry<String, Integer>> top3 = snapshot.top3Topics();
        LocalDateTime now = LocalDateTime.now();

        VillageNewsletterIssueEntity issue = VillageNewsletterIssueEntity.builder()
                .villageId(villageId)
                .newsletterId(newsletterId)
                .frequency(frequency)
                .issueType(VillageNewsletterIssueType.REGULAR)
                // status は onCreate で AGGREGATED になるが、freeze() の前提を明示するため明示指定する。
                .status(VillageNewsletterIssueStatus.AGGREGATED)
                .title(generateTitle(frequency, periodStart))
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .aggregatedAt(now)
                .scheduledPublishAt(scheduledPublishAt)
                .digestPostCount(snapshot.postCount())
                .digestNewMemberCount(snapshot.newMemberCount())
                .digestFestivalCount(snapshot.festivalCount())
                .digestMeetupCount(snapshot.meetupCount())
                .digestRecruitCount(snapshot.recruitCount())
                .digestTopic1Name(topicName(top3, 0))
                .digestTopic1Count(topicCount(top3, 0))
                .digestTopic2Name(topicName(top3, 1))
                .digestTopic2Count(topicCount(top3, 1))
                .digestTopic3Name(topicName(top3, 2))
                .digestTopic3Count(topicCount(top3, 2))
                .build();

        VillageNewsletterIssueEntity saved = issueRepository.save(issue);

        // 集計値は build 時に確定済み。ここで状態のみ FROZEN へ遷移させる（以後 digest_* は不変）。
        try {
            saved.freeze(now, scheduledPublishAt);
        } catch (IllegalStateException e) {
            // AGGREGATED 以外からの凍結＝改ざんに当たる遷移。型付きドメインエラーへ翻訳する（設計書 §4.2）。
            throw new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_ALREADY_FROZEN);
        }
        VillageNewsletterIssueEntity frozen = issueRepository.save(saved);

        auditLogService.record(
                AuditEventType.VILLAGE_NEWSLETTER_ISSUE_FROZEN.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"issueId\":\"" + frozen.getId()
                        + "\",\"frequency\":\"" + frequency
                        + "\",\"periodStart\":\"" + periodStart
                        + "\",\"periodEnd\":\"" + periodEnd
                        + "\",\"postCount\":" + snapshot.postCount()
                        + ",\"newMemberCount\":" + snapshot.newMemberCount() + "}"
        );
        log.info("ニュースレター号を集計・凍結: villageId={} frequency={} periodStart={} periodEnd={} postCount={}",
                villageId, frequency, periodStart, periodEnd, snapshot.postCount());

        return frozen;
    }

    /**
     * 号タイトルの既定値を生成する（村長が後から編集可）。i18n は不要（BE 内部既定文字列・設計書 §4.2）。
     */
    private String generateTitle(VillageNewsletterFrequency frequency, LocalDateTime periodStart) {
        if (frequency == VillageNewsletterFrequency.MONTHLY) {
            return String.format("%d年%02d月 村だより", periodStart.getYear(), periodStart.getMonthValue());
        }
        // WEEKLY: 期間開始日の週として表現する。
        return String.format("%d年%02d月%02d日週 村だより",
                periodStart.getYear(), periodStart.getMonthValue(), periodStart.getDayOfMonth());
    }

    /** TOP3 トピックの指定順位の名前を返す（無ければ null）。 */
    private static String topicName(List<Map.Entry<String, Integer>> top3, int index) {
        return index < top3.size() ? top3.get(index).getKey() : null;
    }

    /** TOP3 トピックの指定順位の件数を返す（無ければ 0）。 */
    private static Integer topicCount(List<Map.Entry<String, Integer>> top3, int index) {
        return index < top3.size() ? top3.get(index).getValue() : 0;
    }
}
