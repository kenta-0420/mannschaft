package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 週間テンプレート horizon 延伸の日次バッチ（F03.4.2 §5.4）。
 *
 * <p><b>実行タイミング:</b> {@code @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Tokyo")}
 * （日次 AM 0:15 JST。リマインドバッチ〔1分間隔〕と時間帯を分離）。
 * 多重起動防止のため {@link SchedulerLock} を付ける
 * （{@link ReservationReminderDispatchBatchService} の作法に倣う）。</p>
 *
 * <p><b>生成対象:</b> 「テンプレごとの差分レンジ」（精査2パス A3 是正）。固定「horizon 末尾 1 日のみ」では
 * weeks=1 の手動 generate 後 day8〜27 が永久空白・generate 後の新規テンプレが永久未生成になる恒久穴が
 * 生じるため、{@link ReservationSlotGenerationService#generateDiffForTeam} がテンプレ 1 行ごとに
 * {@code [max(tomorrow, MAX(slot_date)+1), tomorrow+27日]} を計算して生成する。
 * 通常運転では range = 末尾 1 日に自然収束し、バッチ停止・障害からの復旧も自動で追い付く。</p>
 *
 * <p><b>トランザクション境界:</b> 本メソッドに {@code @Transactional} は付けない。
 * 生成は最悪 13,440 INSERT/チームの巨大単一 tx を避けるため<b>日付単位のチャンク tx</b>
 * （generation service 内の {@code TransactionTemplate}）で行う（§5.2）。
 * 冪等キー（§5.3）により途中失敗でも再実行が安全（コミット済み日付は先読み/UNIQUE でスキップ）。</p>
 *
 * <p><b>失敗の隔離:</b> チーム単位 try/catch（1チームの失敗が他チームを巻き込まない・
 * {@code log.error} で正直に記録。チーム内は日付チャンクごとにコミット済み分が残る=再実行で追い付く）。</p>
 */
@Slf4j
@Service
public class ReservationSlotGenerationBatchService {

    private final ReservationSlotTemplateRepository templateRepository;
    private final ReservationSlotGenerationService generationService;
    private final TeamTimezoneResolver teamTimezoneResolver;

    /** 既存のスライステスト／手動組み立てとの互換用。実運用では Spring の3引数 ctor を使う。 */
    @Autowired
    public ReservationSlotGenerationBatchService(ReservationSlotTemplateRepository templateRepository,
                                                  ReservationSlotGenerationService generationService,
                                                  TeamTimezoneResolver teamTimezoneResolver) {
        this.templateRepository = templateRepository;
        this.generationService = generationService;
        this.teamTimezoneResolver = teamTimezoneResolver;
    }

    public ReservationSlotGenerationBatchService(ReservationSlotTemplateRepository templateRepository,
                                                  ReservationSlotGenerationService generationService) {
        this(templateRepository, generationService, null);
    }

    /**
     * active テンプレを持つ全チームについて horizon 差分を生成する。
     */
    @BatchEndpoint(name = "reservation-slot-generation",
            description = "週間テンプレートから予約枠の horizon（28日先）差分を日次生成する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予約枠の先送りホライズン生成であり、冪等かつ将来分が対象なので再開後に埋め直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "reservationSlotGeneration", lockAtLeastFor = "1m", lockAtMostFor = "30m")
    public void generateDailyHorizon() {
        List<Long> teamIds = templateRepository.findDistinctActiveTeamIds();
        if (teamIds.isEmpty()) {
            return;
        }
        var teamZones = teamTimezoneResolver == null
                ? java.util.Collections.<Long, java.time.ZoneId>emptyMap()
                : teamTimezoneResolver.resolveZones(teamIds);
        /*
        // チーム TZ は一括解決し、チームごとの生成ループで N+1 lookup を発生させない。
        var teamZones = teamTimezoneResolver == null
                ? java.util.Collections.<Long, java.time.ZoneId>emptyMap()
                : teamTimezoneResolver.resolveZones(teamIds); */
        int succeeded = 0;
        for (Long teamId : teamIds) {
            try {
                if (teamTimezoneResolver == null) {
                    generationService.generateDiffForTeam(teamId);
                } else {
                    generationService.generateDiffForTeam(teamId, teamZones.get(teamId));
                }
                succeeded++;
            } catch (Exception e) {
                // 1チームの失敗が他チームを巻き込まないようチーム単位で隔離する（握りつぶさず error 記録）。
                // チーム内は日付チャンクごとにコミット済み分が残るため、翌日の差分レンジが自動で追い付く。
                log.error("週間テンプレート日次生成失敗（次回バッチの差分レンジで自己修復）: teamId={}", teamId, e);
            }
        }
        log.info("週間テンプレート日次生成バッチ: 対象{}チーム中 {}チーム成功", teamIds.size(), succeeded);
    }
}
