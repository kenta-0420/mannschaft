package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 週間テンプレートからの 30 分セル枠生成の<b>単一実装</b>（F03.4.2 §5.1/§5.2/§5.3/§5.4）。
 *
 * <p>手動 generate（{@code ReservationSlotTemplateService}）と日次バッチ
 * （{@code ReservationSlotGenerationBatchService}）の両方がここを呼ぶ（別実装厳禁）。</p>
 *
 * <h2>アルゴリズム（§5.2）</h2>
 * <ul>
 *   <li><b>30 分セル分割が標準</b>: テンプレの帯（例 10:00〜13:00）を 30 分セルへ分割して生成する</li>
 *   <li><b>営業時間突合</b>: 曜日行なし / {@code is_open=FALSE} / 時刻 NULL は「営業時間が定義されていない日」
 *       として帯ごとスキップ（{@code skippedClosedDayCount} 加算・NPE 根絶の防御分岐・F-7b）。
 *       既定営業時間へのフォールバック生成はしない（管理者が意図しない枠を勝手に作らない）</li>
 *   <li><b>冪等（§5.3）</b>: 対象期間の既存セルを 1 クエリで先読みして Set 突合（N+1 回避）。
 *       並行実行ですり抜けた場合は {@code INSERT IGNORE} が UNIQUE 制約 {@code uq_rs_template_cell} の
 *       衝突を 0 行更新として返すため、エラーにせず {@code skippedExistingCount} 扱いにする
 *       （例外方式だと Hibernate セッションが汚染されチャンク内の後続セルを巻き込むため、
 *       IGNORE 方式で同一意味論を実現し DB を最終防御とする —
 *       {@code ReservationSlotRepository#insertGeneratedCellIgnoreDuplicate}）</li>
 *   <li><b>日付単位のチャンク tx（§5.2）</b>: 手動 generate 最悪 13,440 INSERT/チームの巨大単一 tx を避け、
 *       1 日 = 最大 480 INSERT = 1 tx（REQUIRES_NEW）でコミットする。チャンク間で失敗しても
 *       冪等キーにより再実行が安全（既生成分はスキップされ、二重生成も欠損も起きない）</li>
 *   <li><b>予約不可枠（機能B）は生成時にチェックしない（確定方針）</b>: enforcement は runtime overlap
 *       ユーティリティ 1 本（親 §5.B）。生成時にもスキップすると二重管理と「ブロック解除後に枠がない」穴が生じる</li>
 * </ul>
 */
@Slf4j
@Service
public class ReservationSlotGenerationService {

    /** weeks 省略時の既定（=28日先まで・§4）。 */
    private static final int DEFAULT_WEEKS = 4;

    /** 日次バッチの horizon 日数（tomorrow + 27 日 = rolling 28 日・§5.4）。 */
    private static final int BATCH_HORIZON_DAYS = 27;

    /** 30 分セル（§5.2）。 */
    private static final int CELL_MINUTES = 30;

    private final ReservationSlotTemplateRepository templateRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationBusinessHourRepository businessHourRepository;
    private final TransactionTemplate chunkTransactionTemplate;
    private final Clock clock;

    public ReservationSlotGenerationService(ReservationSlotTemplateRepository templateRepository,
                                            ReservationSlotRepository slotRepository,
                                            ReservationBusinessHourRepository businessHourRepository,
                                            PlatformTransactionManager transactionManager,
                                            Clock clock) {
        this.templateRepository = templateRepository;
        this.slotRepository = slotRepository;
        this.businessHourRepository = businessHourRepository;
        // 日付チャンクは呼び出し元の tx（例: readOnly の Service tx）に巻き込まれないよう常に新規 tx で切る。
        this.chunkTransactionTemplate = new TransactionTemplate(transactionManager);
        this.chunkTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * 手動 generate（F03.4.2 §4/§5.2）: チームの active テンプレ全件を対象に
     * 明日〜horizon（weeks*7 日先）までの枠を冪等生成する。
     *
     * @param teamId    チームID
     * @param weeks     何週先まで（1〜4・null は 4）
     * @param createdBy 実行者（生成枠の created_by へ）
     * @return 生成結果カウント
     * @throws BusinessException active テンプレが 0 件（400・状態検証。新規コードなしの汎用 400 — §4）
     */
    public GenerateSlotsResponse generateForTeam(Long teamId, Integer weeks, Long createdBy) {
        int effectiveWeeks = weeks != null ? weeks : DEFAULT_WEEKS;
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1); // 当日は生成しない（当日枠は手動作成の領分）
        LocalDate horizonTo = tomorrow.plusDays(effectiveWeeks * 7L - 1);

        List<ReservationSlotTemplateEntity> templates = templateRepository.findByTeamIdAndIsActiveTrue(teamId);
        if (templates.isEmpty()) {
            // 入力不正ではなく状態検証のため Bean Validation ではなく Service 層で 400 を返す（§4・F-14）。
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "templates", "有効なテンプレートがありません。先にテンプレートを作成してください")));
        }

        // 手動 generate は全テンプレとも horizon 全域が対象。
        Map<UUID, LocalDate> fromByTemplate = new HashMap<>();
        for (ReservationSlotTemplateEntity template : templates) {
            fromByTemplate.put(template.getId(), tomorrow);
        }
        GenerateSlotsResponse response =
                generateInternal(teamId, templates, fromByTemplate, tomorrow, horizonTo, createdBy);
        log.info("週間テンプレート手動生成: teamId={}, weeks={}, generated={}, skippedExisting={}, "
                        + "skippedClosedDay={}, skippedOutsideHours={}",
                teamId, effectiveWeeks, response.getGeneratedCount(), response.getSkippedExistingCount(),
                response.getSkippedClosedDayCount(), response.getSkippedOutsideHoursCount());
        return response;
    }

    /**
     * 日次バッチ用の差分生成（F03.4.2 §5.4）: テンプレ 1 行ごとに差分レンジ
     * {@code [max(tomorrow, MAX(slot_date)+1), tomorrow+27日]} を計算して生成する。
     *
     * <p>{@code lastGeneratedDate} は生成実績（{@code MAX(slot_date)}）から導出する（専用カラムを持たず
     * 二重管理しない）。導出が並行 generate とズレても冪等キー（§5.3）が二重生成を最終防御するため安全。
     * weeks=1 の手動 generate 後の day8〜27 空白・generate 後に作られた新規テンプレの未生成域も
     * このレンジが自己修復する（F-9②）。</p>
     *
     * @param teamId チームID
     * @return 生成結果カウント（active テンプレ 0 件は全カウント 0 で正常 return・バッチはエラーにしない）
     */
    public GenerateSlotsResponse generateDiffForTeam(Long teamId) {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        LocalDate horizonTo = tomorrow.plusDays(BATCH_HORIZON_DAYS);

        List<ReservationSlotTemplateEntity> templates = templateRepository.findByTeamIdAndIsActiveTrue(teamId);
        if (templates.isEmpty()) {
            // 手動 generate（F-14 の 400）と異なりバッチは例外にしない（1チームの状態が他チームを巻き込まない）。
            return emptyResponse(tomorrow, horizonTo);
        }

        Map<UUID, LocalDate> fromByTemplate = new HashMap<>();
        for (ReservationSlotTemplateEntity template : templates) {
            LocalDate lastGenerated = slotRepository.findMaxGeneratedSlotDateByTemplateId(template.getId());
            LocalDate from = (lastGenerated == null) ? tomorrow : maxDate(tomorrow, lastGenerated.plusDays(1));
            // range が空（lastGeneratedDate >= horizon 末尾）のテンプレはスキップ（from を horizon 外へ）。
            fromByTemplate.put(template.getId(), from);
        }
        GenerateSlotsResponse response =
                generateInternal(teamId, templates, fromByTemplate, tomorrow, horizonTo, null);
        log.info("週間テンプレート差分生成（バッチ）: teamId={}, generated={}, skippedExisting={}, "
                        + "skippedClosedDay={}, skippedOutsideHours={}",
                teamId, response.getGeneratedCount(), response.getSkippedExistingCount(),
                response.getSkippedClosedDayCount(), response.getSkippedOutsideHoursCount());
        return response;
    }

    /**
     * 生成の中核（手動/バッチ共通・§5.2 の擬似コードと 1:1）。
     *
     * @param fromByTemplate テンプレごとの生成開始日（この日より前の日付はそのテンプレの対象外）
     */
    private GenerateSlotsResponse generateInternal(Long teamId,
                                                   List<ReservationSlotTemplateEntity> templates,
                                                   Map<UUID, LocalDate> fromByTemplate,
                                                   LocalDate horizonFrom,
                                                   LocalDate horizonTo,
                                                   Long createdBy) {
        // 曜日 → 営業時間（行の無い曜日はキー欠損 = 営業時間未定義としてスキップ・F-7b）
        Map<String, ReservationBusinessHourEntity> businessHours = new HashMap<>();
        for (ReservationBusinessHourEntity hour : businessHourRepository.findByTeamIdOrderByIdAsc(teamId)) {
            businessHours.putIfAbsent(hour.getDayOfWeek(), hour);
        }

        // 冪等の一括先読み（§5.3・N+1 回避）: チーム単位で horizon 全域を 1 クエリ → Set 化してメモリ突合
        Set<String> existingCells = new HashSet<>();
        for (Object[] key : slotRepository.findGeneratedCellKeysByTeamIdAndSlotDateBetween(
                teamId, horizonFrom, horizonTo)) {
            existingCells.add(cellKey((UUID) key[0], (LocalDate) key[1], (LocalTime) key[2]));
        }

        Counts counts = new Counts();
        for (LocalDate date = horizonFrom; !date.isAfter(horizonTo); date = date.plusDays(1)) {
            LocalDate currentDate = date;
            // ★表現一致（B1）: date からの導出は必ず正準の3文字大文字（MON..SUN）へ変換してから突合する
            ReservationDayOfWeek dow = ReservationDayOfWeek.from(currentDate);
            List<ReservationSlotTemplateEntity> dayTemplates = templates.stream()
                    .filter(tpl -> tpl.getDayOfWeek() == dow)
                    .filter(tpl -> !currentDate.isBefore(fromByTemplate.get(tpl.getId())))
                    .toList();
            if (dayTemplates.isEmpty()) {
                continue;
            }
            // 日付単位のチャンク tx（1日 = 最大 480 INSERT = 1 tx・§5.2）
            chunkTransactionTemplate.executeWithoutResult(status ->
                    generateForDate(teamId, currentDate, dow, dayTemplates,
                            businessHours.get(dow.name()), existingCells, createdBy, counts));
        }
        return GenerateSlotsResponse.builder()
                .generatedCount(counts.generated)
                .skippedExistingCount(counts.skippedExisting)
                .skippedClosedDayCount(counts.skippedClosedDay)
                .skippedOutsideHoursCount(counts.skippedOutsideHours)
                .horizonFrom(horizonFrom)
                .horizonTo(horizonTo)
                .build();
    }

    /** 1 日分のセル生成（チャンク tx 内・§5.2 の内側ループ）。 */
    private void generateForDate(Long teamId,
                                 LocalDate date,
                                 ReservationDayOfWeek dow,
                                 List<ReservationSlotTemplateEntity> dayTemplates,
                                 ReservationBusinessHourEntity businessHour,
                                 Set<String> existingCells,
                                 Long createdBy,
                                 Counts counts) {
        for (ReservationSlotTemplateEntity template : dayTemplates) {
            // ★営業時間の防御分岐（NPE 根絶・確定仕様・F-7b）:
            //   (1) 当該曜日の行が存在しない（新規チームは 0 行があり得る — 実測）
            //   (2) is_open=TRUE だが open_time / close_time が NULL（V3.063 実 DDL は時刻 NULL 許容）
            //   いずれも「営業時間が定義されていない日」として定休日と同列にスキップする。
            //   既定営業時間（9:00-18:00 等）へのフォールバック生成はしない。
            if (businessHour == null || !Boolean.TRUE.equals(businessHour.getIsOpen())
                    || businessHour.getOpenTime() == null || businessHour.getCloseTime() == null) {
                counts.skippedClosedDay += template.cellCount();
                continue;
            }
            for (LocalTime cellStart = template.getStartTime();
                 cellStart.isBefore(template.getEndTime());
                 cellStart = cellStart.plusMinutes(CELL_MINUTES)) {
                LocalTime cellEnd = cellStart.plusMinutes(CELL_MINUTES);
                // 境界: close_time ちょうどで終わるセルは生成される（セル全体が営業時間内 — F-7）
                if (cellStart.isBefore(businessHour.getOpenTime())
                        || cellEnd.isAfter(businessHour.getCloseTime())) {
                    counts.skippedOutsideHours++;
                    continue;
                }
                if (existingCells.contains(cellKey(template.getId(), date, cellStart))) {
                    counts.skippedExisting++;
                    continue;
                }
                int inserted = insertCell(teamId, template, date, cellStart, cellEnd, createdBy);
                if (inserted == 1) {
                    counts.generated++;
                } else {
                    // 並行実行で先読みをすり抜けた UNIQUE 衝突（INSERT IGNORE → 0 行）。DB が最終防御（§5.3）。
                    counts.skippedExisting++;
                }
            }
        }
    }

    /**
     * 30 分セル 1 枠の INSERT IGNORE（戻り値 1=挿入・0=UNIQUE 衝突スキップ）。
     *
     * <p>Hibernate ネイティブクエリ経由（{@code ReservationSlotRepository}）で実行し、
     * TIME/DATE/BINARY(16) のバインドをエンティティ永続化と同一の変換規則にする
     * （素の JDBC 直挿入は JVM≠DB タイムゾーン環境で時刻が非対称にずれる実測バグがあった）。</p>
     */
    private int insertCell(Long teamId, ReservationSlotTemplateEntity template,
                           LocalDate date, LocalTime cellStart, LocalTime cellEnd, Long createdBy) {
        return slotRepository.insertGeneratedCellIgnoreDuplicate(
                teamId,
                template.getLineId(),
                template.getStaffUserId(),
                uuidToBytes(template.getId()),
                date,
                cellStart,
                cellEnd,
                template.getCapacity() != null ? template.getCapacity() : 1,
                template.getTitle(),
                template.getPrice(),
                template.getApprovalMode() != null ? template.getApprovalMode().name() : null,
                createdBy);
    }

    private static GenerateSlotsResponse emptyResponse(LocalDate from, LocalDate to) {
        return GenerateSlotsResponse.builder()
                .horizonFrom(from)
                .horizonTo(to)
                .build();
    }

    private static String cellKey(UUID templateId, LocalDate date, LocalTime startTime) {
        return templateId + "|" + date + "|" + startTime;
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** UUID を BINARY(16) 用のバイト列へ変換する（JDBC 直挿入用）。 */
    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    /** 生成カウントのアキュムレータ（チャンク tx ラムダ内から加算するため可変フィールドで持つ）。 */
    private static final class Counts {
        private int generated;
        private int skippedExisting;
        private int skippedClosedDay;
        private int skippedOutsideHours;
    }
}
