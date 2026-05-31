package com.mannschaft.app.team.batch;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamRegionNormalizer;
import com.mannschaft.app.team.service.TeamRegionNormalizer.MatchStage;
import com.mannschaft.app.team.service.TeamRegionNormalizer.ResolvedRegion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F22.1 市 Phase 2 足場C 第一陣: teams の自由入力住所をコード化するバックフィル
 *  （ドライラン基盤）。
 *
 * <p>全 teams を走査し {@link TeamRegionNormalizer} で名称→コードを逆引きする。
 * 既定の {@code dryRun=true} では UPDATE を一切行わず、解決結果とマッチ率を集計ログ出力する。
 * 実書き込み（{@code dryRun=false}）パスも実装するが、第一陣では起動しない
 * （殿の別裁可で後日実行）。冪等であり、既にコードを持つ行はスキップする。</p>
 *
 * <h2>起動方法（殿が後で叩く用）</h2>
 * <ul>
 *   <li>ドライラン: {@code teamRegionBackfillService.run(true)} を任意の管理経路から呼ぶ。
 *       何も書き込まず、集計のみログ出力する。</li>
 *   <li>本実行: {@code teamRegionBackfillService.run(false)}（別裁可後にのみ実行）。</li>
 * </ul>
 *
 * <p>本クラスは {@code @Scheduled} を持たず、{@code @BatchEndpoint} も付与しない
 * （第一陣では自動起動させないため）。手動呼び出し用の Bean としてのみ存在する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamRegionBackfillService {

    /** 1 ページあたりの走査件数。 */
    private static final int CHUNK_SIZE = 500;

    private final TeamRepository teamRepository;
    private final TeamRegionNormalizer normalizer;

    /**
     * バックフィルを実行する。
     *
     * @param dryRun {@code true}（既定の使い方）なら UPDATE せず集計のみ。
     *               {@code false} は実書き込み（別裁可後にのみ呼ぶ）。
     * @return 集計結果
     */
    @Transactional
    public BackfillResult run(boolean dryRun) {
        log.info("[TeamRegionBackfill] 開始 dryRun={}", dryRun);
        BackfillResult result = new BackfillResult();

        int pageIndex = 0;
        Page<TeamEntity> page;
        do {
            page = teamRepository.findAll(PageRequest.of(pageIndex, CHUNK_SIZE));
            for (TeamEntity team : page.getContent()) {
                processOne(team, dryRun, result);
            }
            pageIndex++;
        } while (page.hasNext());

        log.info("[TeamRegionBackfill] 完了 dryRun={} {}", dryRun, result.summary());
        return result;
    }

    private void processOne(TeamEntity team, boolean dryRun, BackfillResult result) {
        result.total++;

        // 冪等性: 既にコードがある行はスキップ（再実行で上書きしない）。
        if (team.getPrefectureCode() != null || team.getCityCode() != null) {
            result.alreadyCoded++;
            return;
        }

        ResolvedRegion resolved = normalizer.normalize(team.getPrefecture(), team.getCity());

        switch (resolved.matchStage()) {
            case CITY -> result.matchedCity++;
            case PREFECTURE_ONLY -> result.matchedPrefectureOnly++;
            case NONE -> result.unmatched++;
            // MatchStage は3値で全列挙済み。将来値が増えたら暗黙に unmatched 集計せず気付けるよう fail-loud。
            default -> throw new IllegalStateException("未知の MatchStage: " + resolved.matchStage());
        }

        if (log.isDebugEnabled()) {
            log.debug("[TeamRegionBackfill] teamId={} 旧pref='{}' 旧city='{}' -> prefCode={} cityCode={} stage={}",
                    team.getId(), team.getPrefecture(), team.getCity(),
                    resolved.prefectureCode(), resolved.cityCode(), resolved.matchStage());
        }

        if (!dryRun && resolved.matchStage() != MatchStage.NONE) {
            // 本実行パス（第一陣では呼ばれない）。解決できた分のみ反映する。
            team.updateRegionCodes(resolved.prefectureCode(), resolved.cityCode());
            teamRepository.save(team);
            result.updated++;
        }
    }

    /**
     * バックフィル集計結果。マッチ率（県まで/市まで/未マッチ）を保持する。
     */
    public static class BackfillResult {
        /** 走査した総件数。 */
        public int total;
        /** 既にコードを持っていてスキップした件数。 */
        public int alreadyCoded;
        /** 市区町村まで解決できた件数。 */
        public int matchedCity;
        /** 都道府県までのみ解決できた件数。 */
        public int matchedPrefectureOnly;
        /** 何も解決できなかった件数。 */
        public int unmatched;
        /** 実書き込みで UPDATE した件数（dryRun=true では常に 0）。 */
        public int updated;

        /**
         * 集計対象（既存コード保有を除いた処理対象）の件数。
         */
        public int processed() {
            return matchedCity + matchedPrefectureOnly + unmatched;
        }

        /**
         * ログ出力用サマリ文字列。
         */
        public String summary() {
            int processed = processed();
            double cityRate = processed == 0 ? 0.0 : (double) matchedCity / processed * 100.0;
            double prefRate = processed == 0 ? 0.0
                    : (double) (matchedCity + matchedPrefectureOnly) / processed * 100.0;
            return String.format(
                    "total=%d alreadyCoded=%d processed=%d matchedCity=%d matchedPrefectureOnly=%d "
                            + "unmatched=%d updated=%d cityMatchRate=%.1f%% prefMatchRate=%.1f%%",
                    total, alreadyCoded, processed, matchedCity, matchedPrefectureOnly,
                    unmatched, updated, cityRate, prefRate);
        }
    }
}
