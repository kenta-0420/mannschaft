package com.mannschaft.app.match.service;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.entity.PlayerAppearanceEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import com.mannschaft.app.match.repository.PlayerAppearanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * F08.10 出場時間自動算出サービス（02 §E）。
 *
 * <p>出場時間は手入力させず {@code match_events} から自動算出する（GoalNote 上位互換の核）。
 * イベントの追加・編集・削除のたびに、当該 match の {@code player_appearances} を
 * <b>フル再計算 upsert</b> する（差分計算ではなくフル再構築・症状を隠さず整合性を担保＝根治・02 §E.2）。</p>
 *
 * <h3>算出ルール（02 §E.1・複数交代/再出場対応）</h3>
 * <pre>
 *   1 選手のイベントを時系列（period, minute, sort_seq）に並べ、in/out 区間を組み立てる:
 *     STARTER                       → 区間開始（in=0）
 *     SUB_IN                        → 区間開始（in=minute）
 *     SUB_OUT                       → 開いている区間を閉じる（out=minute）
 *     RED_CARD / SECOND_YELLOW      → 開いている区間を閉じる（out=minute・以降出場不可）
 *     試合終了時に開いたままの区間    → out=duration_minutes（延長込みの試合通算分）で閉じる
 *   computed_minutes = Σ max(0, out_i - in_i)
 * </pre>
 *
 * <p><b>楽観ロック回避</b>: 本サービスは {@code matches.version} に一切触れない。
 * {@code player_appearances} のみを更新する（共同記録での matches 行奪い合いを回避・02 §E.2）。</p>
 *
 * <p><b>破壊耐性</b>: フル同期の削除対象は<b>変更権限のある team_side 分に限定</b>する
 * （相手チームの appearance を巻き添えで削除しない・02 §E.5a）。</p>
 *
 * <p>{@code @Transactional} は match ドメイン内に閉じる（原則 5）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §E</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayingTimeCalculationService {

    /** 退出を確定させるイベント（区間を閉じる）。 */
    private static final Set<MatchEventType> OUT_EVENTS =
            EnumSet.of(MatchEventType.SUB_OUT, MatchEventType.RED_CARD, MatchEventType.SECOND_YELLOW);

    private final MatchEventRepository matchEventRepository;
    private final PlayerAppearanceRepository playerAppearanceRepository;

    /**
     * 指定 match の出場記録をフル再計算して upsert する（イベント変更時に呼ぶ・02 §E.2）。
     *
     * <p>{@code editableTeamSides} に含まれる side の appearance のみを削除同期対象とする
     * （相手チーム分を破壊しない・02 §E.5a）。{@code null} を渡すと両 side を同期対象とする
     * （記録係による単独記録・全 side 編集権限がある場合）。</p>
     *
     * @param match            対象試合（duration_minutes・delete 同期の基準）
     * @param editableTeamSides 削除同期を許可する team_side 集合（{@code null}=全 side）
     */
    @Transactional
    public void recalculate(MatchEntity match, Set<TeamSide> editableTeamSides) {
        // ターン制（将棋/囲碁）・採点競技（フィギュア/体操）は STARTER/SUB イベントが存在せず
        // 出場区間が組み立たないため、出場時間算出を起動しない（01 §D.6 / §D.8・症状を隠さず
        // 「出場交代の概念が無い」を素直に表現）。
        StateModel stateModel = resolveStateModel(match);
        if (stateModel == StateModel.TURN_BASED || stateModel == StateModel.SCORED) {
            return;
        }
        UUID matchId = match.getId();
        List<MatchEventEntity> events =
                matchEventRepository.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId);

        // 選手キーごとにイベントをグルーピング（登録選手=userId / 未登録=jersey+name+side・01 §D.4）
        Map<PlayerKey, List<MatchEventEntity>> byPlayer = new LinkedHashMap<>();
        for (MatchEventEntity e : events) {
            if (!affectsAppearance(e.getEventType())) {
                // GOAL/ASSIST/YELLOW_CARD 等は出場区間に影響しない（02 §2.1 表）
                continue;
            }
            byPlayer.computeIfAbsent(PlayerKey.of(e), k -> new ArrayList<>()).add(e);
        }

        Integer duration = match.getDurationMinutes();

        // 既存 appearance を side 別に分類（削除同期判定用）
        List<PlayerAppearanceEntity> existing = playerAppearanceRepository.findByMatchId(matchId);

        // 今回計算で残す appearance のキー集合（削除同期で参照）
        Set<PlayerKey> currentKeys = byPlayer.keySet();

        for (Map.Entry<PlayerKey, List<MatchEventEntity>> entry : byPlayer.entrySet()) {
            PlayerKey key = entry.getKey();
            AppearanceResult result = computeAppearance(entry.getValue(), duration);
            upsertAppearance(matchId, key, result, existing);
        }

        // フル同期: events に現れなくなった選手の appearance を削除（編集権限スコープ内に限定・02 §E.5a）
        for (PlayerAppearanceEntity ap : existing) {
            PlayerKey apKey = PlayerKey.of(ap);
            if (currentKeys.contains(apKey)) {
                continue;
            }
            if (editableTeamSides != null && !editableTeamSides.contains(ap.getTeamSide())) {
                // 編集権限のない side の appearance は巻き添え削除しない（相手分を破壊しない）
                continue;
            }
            playerAppearanceRepository.delete(ap);
        }
    }

    /**
     * 1 選手のイベント列から出場区間を組み立て出場分を算出する（02 §E.1）。
     *
     * <p>パッケージ可視性で UT から直接検証できるよう公開する。</p>
     *
     * @param playerEvents 当該選手の出場影響イベント（時系列ソート済み）
     * @param duration     試合通算分（NULL=未設定）
     * @return 算出結果
     */
    AppearanceResult computeAppearance(List<MatchEventEntity> playerEvents, Integer duration) {
        // 時系列に整列（呼び出し側でソート済みだが防御的に再ソート）
        List<MatchEventEntity> sorted = new ArrayList<>(playerEvents);
        sorted.sort(Comparator
                .comparing((MatchEventEntity e) -> e.getPeriod().ordinal())
                .thenComparing(e -> e.getMinute() == null ? Integer.MAX_VALUE : e.getMinute())
                .thenComparingInt(MatchEventEntity::getSortSeq));

        boolean starter = false;
        Integer firstIn = null;
        Integer lastOut = null;
        int total = 0;
        boolean unknown = false; // out 未確定（duration 未設定）の区間が残ったか

        Integer openIn = null;   // 現在開いている区間の in（null=開いていない）
        TeamSide side = sorted.isEmpty() ? TeamSide.HOME : sorted.get(0).getTeamSide();

        for (MatchEventEntity e : sorted) {
            side = e.getTeamSide();
            MatchEventType type = e.getEventType();
            Integer minute = e.getMinute();

            if (type == MatchEventType.STARTER) {
                starter = true;
                openIn = 0;
                if (firstIn == null) {
                    firstIn = 0;
                }
            } else if (type == MatchEventType.SUB_IN) {
                int in = minute != null ? minute : 0;
                openIn = in;
                if (firstIn == null) {
                    firstIn = in;
                }
            } else if (OUT_EVENTS.contains(type)) {
                // 開いている区間を閉じる。SUB_OUT と退場が同一区間にある異常は「より早い分」を out とする。
                int out = minute != null ? minute : (openIn != null ? openIn : 0);
                if (openIn != null) {
                    int segment = Math.max(0, out - openIn);
                    total += segment;
                    openIn = null;
                }
                lastOut = out;
            }
        }

        // 試合終了時に開いたままの区間を duration で閉じる（延長込みの試合通算分・02 §E.1）
        if (openIn != null) {
            if (duration == null) {
                // out 未確定かつ試合長未設定 → computed_minutes を確定できない（ゼロ埋め・握り潰し禁止・02 §E.1）
                unknown = true;
            } else {
                int segment = Math.max(0, duration - openIn);
                total += segment;
                lastOut = duration;
            }
        }

        Integer computed = unknown ? null : total;
        return new AppearanceResult(starter, firstIn, lastOut, computed, side);
    }

    /**
     * 試合の状態モデル類型を解決する（列保持値を優先・未設定は sport から導出・01 §D.6）。
     */
    private StateModel resolveStateModel(MatchEntity match) {
        if (match.getStateModel() != null) {
            return match.getStateModel();
        }
        return match.getSport() != null ? match.getSport().stateModel() : StateModel.CONTINUOUS_TIME;
    }

    /**
     * イベント種別が出場区間（appearance）に影響するか（02 §2.1 表）。
     * STARTER/SUB_IN/SUB_OUT/RED_CARD/SECOND_YELLOW のみが in/out に関与する。
     */
    private boolean affectsAppearance(MatchEventType type) {
        return type == MatchEventType.STARTER
                || type == MatchEventType.SUB_IN
                || OUT_EVENTS.contains(type);
    }

    private void upsertAppearance(UUID matchId, PlayerKey key, AppearanceResult result,
                                  List<PlayerAppearanceEntity> existing) {
        PlayerAppearanceEntity target = existing.stream()
                .filter(ap -> PlayerKey.of(ap).equals(key))
                .findFirst()
                .orElse(null);

        // owning_team_id は team_side から導出する（HOME/AWAY ↔ teamId のマッピングは
        // 呼び出し元 MatchEventService がイベント記録時に recorded_by_team_id へ正しく刻む。
        // appearance の owning_team_id は「どのチームが編集権を持つか」を表すため、
        // 既存行があればそれを保持し、無ければイベント側の recorded_by_team_id を引き継ぐ）。
        Long owningTeamId = target != null ? target.getOwningTeamId() : key.owningTeamId();

        if (target == null) {
            PlayerAppearanceEntity created = PlayerAppearanceEntity.builder()
                    .matchId(matchId)
                    .playerUserId(key.playerUserId())
                    .playerName(key.playerName())
                    .teamSide(result.teamSide())
                    .starter(result.starter())
                    .jerseyNumber(key.jerseyNumber())
                    .firstInMinute(result.firstInMinute())
                    .lastOutMinute(result.lastOutMinute())
                    .computedMinutes(result.computedMinutes())
                    .owningTeamId(owningTeamId)
                    .build();
            playerAppearanceRepository.save(created);
        } else {
            target.setTeamSide(result.teamSide());
            target.setStarter(result.starter());
            target.setFirstInMinute(result.firstInMinute());
            target.setLastOutMinute(result.lastOutMinute());
            target.setComputedMinutes(result.computedMinutes());
            target.setJerseyNumber(key.jerseyNumber());
            playerAppearanceRepository.save(target);
        }
    }

    /**
     * 出場区間算出の結果（02 §E.1 の代表値群）。
     *
     * @param starter         STARTER イベントがあったか
     * @param firstInMinute   最初の出場開始分（代表値・NULL=出場なし）
     * @param lastOutMinute   最後の退場分（代表値）
     * @param computedMinutes 全区間合計の出場分（NULL=不明＝out 未確定かつ duration 未設定）
     * @param teamSide        所属サイド
     */
    public record AppearanceResult(
            boolean starter,
            Integer firstInMinute,
            Integer lastOutMinute,
            Integer computedMinutes,
            TeamSide teamSide) {
    }

    /**
     * 選手の同一性キー（01 §D.4）。
     * 登録選手は {@code player_user_id} を、未登録選手は {@code (jersey_number, player_name, team_side)} を使う。
     * {@code owningTeamId} はキー同一性には含めず、appearance 生成時の team 引き継ぎにのみ用いる。
     */
    private record PlayerKey(
            Long playerUserId,
            Integer jerseyNumber,
            String playerName,
            TeamSide teamSide,
            Long owningTeamId) {

        static PlayerKey of(MatchEventEntity e) {
            return new PlayerKey(
                    e.getPlayerUserId(),
                    e.getPlayerUserId() == null ? e.getJerseyNumber() : null,
                    e.getPlayerUserId() == null ? e.getPlayerName() : null,
                    e.getTeamSide(),
                    e.getRecordedByTeamId());
        }

        static PlayerKey of(PlayerAppearanceEntity ap) {
            return new PlayerKey(
                    ap.getPlayerUserId(),
                    ap.getPlayerUserId() == null ? ap.getJerseyNumber() : null,
                    ap.getPlayerUserId() == null ? ap.getPlayerName() : null,
                    ap.getTeamSide(),
                    ap.getOwningTeamId());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PlayerKey other)) {
                return false;
            }
            // owningTeamId はキー同一性に含めない（再計算で team が後から判明しても同一選手として扱う）
            if (playerUserId != null || other.playerUserId != null) {
                return Objects.equals(playerUserId, other.playerUserId);
            }
            return Objects.equals(jerseyNumber, other.jerseyNumber)
                    && Objects.equals(playerName, other.playerName)
                    && teamSide == other.teamSide;
        }

        @Override
        public int hashCode() {
            if (playerUserId != null) {
                return Objects.hash(playerUserId);
            }
            return Objects.hash(jerseyNumber, playerName, teamSide);
        }
    }
}
