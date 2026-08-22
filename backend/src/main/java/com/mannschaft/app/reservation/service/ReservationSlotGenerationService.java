package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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

    /**
     * テンプレ保存＝同期自動生成・営業時間変更差分生成の horizon 日数
     * （tomorrow + 27 日 = rolling 28 日・F03.4.5 §3.1/§3.2）。日次バッチと同一 rolling horizon。
     */
    private static final int TEMPLATE_HORIZON_DAYS = 27;

    /**
     * 臨時営業（単日テンプレ適用・§3.3.2）の対象日上限（今日から 90 日以内）。
     * horizon（28日）の外への遠未来生成を防ぐ意図的な別値（§12 horizon 一覧）。
     */
    private static final int SINGLE_DAY_MAX_AHEAD_DAYS = 90;

    /** 30 分セル（§5.2）。 */
    private static final int CELL_MINUTES = 30;

    private final ReservationSlotTemplateRepository templateRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationBusinessHourRepository businessHourRepository;
    private final TransactionTemplate chunkTransactionTemplate;
    private final Clock clock;
    private final TeamTimezoneResolver teamTimezoneResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public ReservationSlotGenerationService(ReservationSlotTemplateRepository templateRepository,
                                            ReservationSlotRepository slotRepository,
                                            ReservationBusinessHourRepository businessHourRepository,
                                            PlatformTransactionManager transactionManager,
                                            Clock clock,
                                            TeamTimezoneResolver teamTimezoneResolver) {
        this.templateRepository = templateRepository;
        this.slotRepository = slotRepository;
        this.businessHourRepository = businessHourRepository;
        // 日付チャンクは呼び出し元の tx（例: readOnly の Service tx）に巻き込まれないよう常に新規 tx で切る。
        this.chunkTransactionTemplate = new TransactionTemplate(transactionManager);
        this.chunkTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.teamTimezoneResolver = teamTimezoneResolver;
    }

    public ReservationSlotGenerationService(ReservationSlotTemplateRepository templateRepository,
                                            ReservationSlotRepository slotRepository,
                                            ReservationBusinessHourRepository businessHourRepository,
                                            PlatformTransactionManager transactionManager,
                                            Clock clock) {
        this(templateRepository, slotRepository, businessHourRepository, transactionManager, clock, null);
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
        LocalDate tomorrow = teamLocalDate(teamId).plusDays(1); // チーム現地日の翌日から生成
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
                generateInternal(teamId, templates, fromByTemplate, tomorrow, horizonTo, createdBy, false);
        log.info("週間テンプレート手動生成: teamId={}, weeks={}, generated={}, skippedExisting={}, "
                        + "skippedClosedDay={}, skippedOutsideHours={}",
                teamId, effectiveWeeks, response.getGeneratedCount(), response.getSkippedExistingCount(),
                response.getSkippedClosedDayCount(), response.getSkippedOutsideHoursCount());
        return response;
    }

    /**
     * テンプレ保存＝同期自動生成（F03.4.5 §3.1）: <b>当該テンプレ 1 行のみ</b>を対象に
     * horizon 28 日（{@code [tomorrow, tomorrow+27]}）を冪等生成する。
     *
     * <p>チーム全域 generate（最悪 13,440 INSERT）を CRUD 応答に載せず、単一テンプレ scope
     * （最大 96 INSERT = 24セル × 同一曜日 4 回）に限定する。他テンプレの horizon 延伸は
     * 日次バッチの責務のまま。<b>本メソッドは保存 tx コミット後・{@code @Transactional} の外側から
     * 呼ぶこと</b>（保存 tx の内側から呼ぶと FK {@code fk_rs_template} の未コミット親行を
     * REQUIRES_NEW チャンク tx が参照して自己デッドロックする・§3.1 の⚠罠）。</p>
     *
     * @param teamId    チームID
     * @param template  対象テンプレ（保存済みの 1 行）
     * @param createdBy 実行者（生成枠の created_by へ）
     * @return 生成結果カウント
     */
    public GenerateSlotsResponse generateForTemplate(Long teamId, ReservationSlotTemplateEntity template,
                                                     Long createdBy) {
        return generateForTemplates(teamId, List.of(template), createdBy);
    }

    /**
     * 複数テンプレ scope の同期自動生成（F03.4.5 §3.2）: 指定テンプレ群のみを対象に
     * horizon 28 日を冪等生成する。営業時間 PUT の「変更のあった曜日の active テンプレ」生成に使う。
     *
     * <p>{@code templates} が空なら生成せず空カウントを返す（変更曜日にテンプレが無い正常ケース）。
     * tx 境界は {@link #generateForTemplate} と同一規約（保存 tx コミット後・外側実行）。</p>
     *
     * @param teamId    チームID
     * @param templates 対象テンプレ群（active 前提・呼び出し側でフィルタ済み）
     * @param createdBy 実行者
     * @return 生成結果カウント
     */
    public GenerateSlotsResponse generateForTemplates(Long teamId,
                                                      List<ReservationSlotTemplateEntity> templates,
                                                      Long createdBy) {
        LocalDate tomorrow = teamLocalDate(teamId).plusDays(1);
        LocalDate horizonTo = tomorrow.plusDays(TEMPLATE_HORIZON_DAYS);
        if (templates.isEmpty()) {
            return emptyResponse(tomorrow, horizonTo);
        }
        Map<UUID, LocalDate> fromByTemplate = new HashMap<>();
        for (ReservationSlotTemplateEntity template : templates) {
            fromByTemplate.put(template.getId(), tomorrow);
        }
        GenerateSlotsResponse response =
                generateInternal(teamId, templates, fromByTemplate, tomorrow, horizonTo, createdBy, false);
        log.info("週間テンプレート同期自動生成: teamId={}, templateCount={}, generated={}, skippedExisting={}, "
                        + "skippedClosedDay={}, skippedOutsideHours={}",
                teamId, templates.size(), response.getGeneratedCount(), response.getSkippedExistingCount(),
                response.getSkippedClosedDayCount(), response.getSkippedOutsideHoursCount());
        return response;
    }

    /**
     * 臨時営業（単日テンプレ適用・F03.4.5 §3.3.2）: 指定日に、指定曜日（省略時=実曜日）の
     * active テンプレ構成で 30 分セルを一括生成する。<b>営業時間突合をスキップ</b>する
     * （臨時営業は定休日/時間外が前提）。冪等キー {@code uq_rs_template_cell} がそのまま効き、
     * 同一日への再実行はスキップされる。
     *
     * <p>§4 の定期予約不可枠・同日の全日休業があっても<b>生成はブロックしない</b>
     * （生成する・runtime で落とすの一貫方針 §4.2/§3.3.2）。horizon 外（tomorrow+28〜+90日）にも
     * {@code template_id} 付きセルを作れる唯一の経路だが、日次バッチのウォーターマークは horizon 上限で
     * クランプ導出（{@link #generateDiffForTeam}）されるため通常の週次生成は欠落しない（§3.4/AC S-8③）。</p>
     *
     * @param teamId          チームID
     * @param date            臨時営業する日（明日以降・今日から90日以内）
     * @param sourceDayOfWeek 適用する曜日ダイヤ（null=date の実曜日）
     * @param createdBy       実行者
     * @return 生成結果カウント（営業時間チェックなしのため closed/outside 系は常に 0）
     * @throws BusinessException 当日・過去日は 400=PAST_DATE_SLOT(023)・91日以降は汎用 400・対象曜日テンプレ 0 件は 400
     */
    public GenerateSlotsResponse generateSingleDay(Long teamId, LocalDate date,
                                                   ReservationDayOfWeek sourceDayOfWeek, Long createdBy) {
        LocalDate today = teamLocalDate(teamId);
        LocalDate tomorrow = today.plusDays(1);
        if (date == null || date.isBefore(tomorrow)) {
            // 当日・過去は「当日枠は手動作成の領分」原則と統一して 400=023 再利用（§3.3.2）。
            throw new BusinessException(ReservationErrorCode.PAST_DATE_SLOT);
        }
        if (date.isAfter(today.plusDays(SINGLE_DAY_MAX_AHEAD_DAYS))) {
            // horizon 外の遠未来生成を防ぐ（汎用 400・新規コードなし）。
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "date", "臨時営業は今日から90日以内の日付を指定してください")));
        }
        ReservationDayOfWeek sourceDow = sourceDayOfWeek != null ? sourceDayOfWeek : ReservationDayOfWeek.from(date);
        List<ReservationSlotTemplateEntity> dayTemplates = templateRepository.findByTeamIdAndIsActiveTrue(teamId)
                .stream()
                .filter(tpl -> tpl.getDayOfWeek() == sourceDow)
                .toList();
        if (dayTemplates.isEmpty()) {
            // 対象曜日に active テンプレが 1 行もない（状態検証・generate の 400 と同作法）。
            throw new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(
                    "templates", "この曜日のテンプレートがありません")));
        }

        // 冪等の先読み（対象日 1 日ぶん）。並行実行は INSERT IGNORE が最終防御（§5.3）。
        Set<String> existingCells = new HashSet<>();
        for (Object[] key : slotRepository.findGeneratedCellKeysByTeamIdAndSlotDateBetween(teamId, date, date)) {
            existingCells.add(cellKey((UUID) key[0], (LocalDate) key[1], (LocalTime) key[2]));
        }

        Counts counts = new Counts();
        LocalDate targetDate = date;
        // 単日 = 1 チャンク tx（REQUIRES_NEW）。営業時間チェックはスキップ（skipBusinessHours=true）。
        chunkTransactionTemplate.executeWithoutResult(status ->
                generateForDate(teamId, targetDate, sourceDow, dayTemplates, null, existingCells, createdBy,
                        counts, true));
        log.info("臨時営業（単日テンプレ適用）: teamId={}, date={}, sourceDow={}, generated={}, skippedExisting={}",
                teamId, date, sourceDow, counts.generated, counts.skippedExisting);
        return GenerateSlotsResponse.builder()
                .generatedCount(counts.generated)
                .skippedExistingCount(counts.skippedExisting)
                .skippedClosedDayCount(counts.skippedClosedDay)
                .skippedOutsideHoursCount(counts.skippedOutsideHours)
                .horizonFrom(date)
                .horizonTo(date)
                .build();
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
        ZoneId teamZone = teamTimezoneResolver == null ? null : teamTimezoneResolver.resolveZone(teamId);
        return generateDiffForTeam(teamId, teamZone);
    }

    /** バッチが一括解決した TZ を渡す差分生成。チームごとの再照会を発生させない。 */
    public GenerateSlotsResponse generateDiffForTeam(Long teamId, ZoneId teamZone) {
        LocalDate tomorrow = teamLocalDate(teamId, teamZone).plusDays(1);
        LocalDate horizonTo = tomorrow.plusDays(BATCH_HORIZON_DAYS);

        List<ReservationSlotTemplateEntity> templates = templateRepository.findByTeamIdAndIsActiveTrue(teamId);
        if (templates.isEmpty()) {
            // 手動 generate（F-14 の 400）と異なりバッチは例外にしない（1チームの状態が他チームを巻き込まない）。
            return emptyResponse(tomorrow, horizonTo);
        }

        Map<UUID, LocalDate> fromByTemplate = new HashMap<>();
        for (ReservationSlotTemplateEntity template : templates) {
            // ★ウォーターマークのクランプ（F03.4.2 §5.4 追補・F03.4.5 §3.4/S-8③）:
            //   臨時営業（generate-single-day・最大+90日）が horizon 外に template_id 付きセルを作ると、
            //   素の MAX(slot_date) ではウォーターマークが跳ねて range が恒久に空 → 当該テンプレの通常週次枠が
            //   最大2ヶ月未生成になる。MAX を horizon 上限（tomorrow+27）でクランプして根治する。
            LocalDate lastGenerated =
                    slotRepository.findMaxGeneratedSlotDateByTemplateIdClamped(template.getId(), horizonTo);
            LocalDate from = (lastGenerated == null) ? tomorrow : maxDate(tomorrow, lastGenerated.plusDays(1));
            // range が空（lastGeneratedDate >= horizon 末尾）のテンプレはスキップ（from を horizon 外へ）。
            fromByTemplate.put(template.getId(), from);
        }
        GenerateSlotsResponse response =
                generateInternal(teamId, templates, fromByTemplate, tomorrow, horizonTo, null, false);
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
                                                   Long createdBy,
                                                   boolean skipBusinessHours) {
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

        // コミット済みチャンクぶんの累計（部分失敗時に SlotGenerationPartialException で報告する・§3.1 契約）。
        Counts committed = new Counts();
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
            // 日付単位のチャンク tx（1日 = 最大 480 INSERT = 1 tx・§5.2）。
            // カウントは<b>チャンクローカル</b>に取り、tx コミット成功後に committed へ合算する。
            // チャンク tx が失敗して rollback された場合、そのチャンクの件数は committed に含めない
            // （在庫が DB にコミットされていないため・§3.1「カウントはコミット済みチャンク分」）。
            Counts chunkCounts = new Counts();
            try {
                chunkTransactionTemplate.executeWithoutResult(status ->
                        generateForDate(teamId, currentDate, dow, dayTemplates,
                                businessHours.get(dow.name()), existingCells, createdBy, chunkCounts,
                                skipBusinessHours));
            } catch (RuntimeException chunkFailure) {
                // 先行チャンクは既にコミット済み。その実件数を例外に載せて上位（保存フローのコントローラ）が
                // トーストに正直な件数を出せるようにする（0 件で握り潰さない＝症状の黙殺を避ける・根治原則）。
                throw new SlotGenerationPartialException(
                        buildResponse(committed, horizonFrom, horizonTo), chunkFailure);
            }
            committed.add(chunkCounts);
        }
        return buildResponse(committed, horizonFrom, horizonTo);
    }

    /** 累計カウント＋horizon から生成結果 DTO を組み立てる。 */
    private static GenerateSlotsResponse buildResponse(Counts counts, LocalDate from, LocalDate to) {
        return GenerateSlotsResponse.builder()
                .generatedCount(counts.generated)
                .skippedExistingCount(counts.skippedExisting)
                .skippedClosedDayCount(counts.skippedClosedDay)
                .skippedOutsideHoursCount(counts.skippedOutsideHours)
                .horizonFrom(from)
                .horizonTo(to)
                .build();
    }

    /**
     * 1 日分のセル生成（チャンク tx 内・§5.2 の内側ループ）。
     *
     * @param skipBusinessHours 臨時営業（§3.3.2）で {@code true}: 営業時間突合を丸ごとスキップし、
     *                          定休日/時間外でも全セルを生成する（closed/outside 系カウントは加算しない）。
     */
    private void generateForDate(Long teamId,
                                 LocalDate date,
                                 ReservationDayOfWeek dow,
                                 List<ReservationSlotTemplateEntity> dayTemplates,
                                 ReservationBusinessHourEntity businessHour,
                                 Set<String> existingCells,
                                 Long createdBy,
                                 Counts counts,
                                 boolean skipBusinessHours) {
        for (ReservationSlotTemplateEntity template : dayTemplates) {
            // ★営業時間の防御分岐（NPE 根絶・確定仕様・F-7b）:
            //   (1) 当該曜日の行が存在しない（新規チームは 0 行があり得る — 実測）
            //   (2) is_open=TRUE だが open_time / close_time が NULL（V3.063 実 DDL は時刻 NULL 許容）
            //   いずれも「営業時間が定義されていない日」として定休日と同列にスキップする。
            //   既定営業時間（9:00-18:00 等）へのフォールバック生成はしない。
            //   ただし臨時営業（skipBusinessHours）は定休日/時間外が前提のため突合を丸ごと省く（§3.3.2）。
            if (!skipBusinessHours
                    && (businessHour == null || !Boolean.TRUE.equals(businessHour.getIsOpen())
                    || businessHour.getOpenTime() == null || businessHour.getCloseTime() == null)) {
                counts.skippedClosedDay += template.cellCount();
                continue;
            }
            boolean overnight = Boolean.TRUE.equals(template.getEndsNextDay());
            long cellCount = SlotTimeValidator.durationMinutes(
                    template.getStartTime(), template.getEndTime(), overnight) / CELL_MINUTES;
            for (long cellIndex = 0; cellIndex < cellCount; cellIndex++) {
                LocalTime cellStart = template.getStartTime().plusMinutes(cellIndex * CELL_MINUTES);
                LocalTime cellEnd = cellStart.plusMinutes(CELL_MINUTES);
                if (!cellEnd.isAfter(cellStart)) {
                    cellEnd = LocalTime.MIDNIGHT;
                }
                // 境界: close_time ちょうどで終わるセルは生成される（セル全体が営業時間内 — F-7）
                if (!skipBusinessHours
                        && !isWithinBusinessHours(cellIndex * CELL_MINUTES, businessHour, cellStart, cellEnd)) {
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
        if (Boolean.TRUE.equals(template.getEndsNextDay())) {
            return slotRepository.insertGeneratedOvernightCellIgnoreDuplicate(
                    teamId, template.getLineId(), template.getStaffUserId(), uuidToBytes(template.getId()),
                    date, cellStart, cellEnd, template.getCapacity() != null ? template.getCapacity() : 1,
                    template.getTitle(), template.getPrice(),
                    template.getApprovalMode() != null ? template.getApprovalMode().name() : null, createdBy);
        }
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

    private LocalDate teamLocalDate(Long teamId) {
        ZoneId zone = teamTimezoneResolver == null ? null : teamTimezoneResolver.resolveZone(teamId);
        return teamLocalDate(teamId, zone);
    }

    private LocalDate teamLocalDate(Long teamId, ZoneId resolvedZone) {
        ZoneId zone = resolvedZone == null
                ? ZoneId.of("Asia/Tokyo") : resolvedZone;
        return LocalDate.now(clock.withZone(zone));
    }

    private static boolean isWithinBusinessHours(long cellOffsetMinutes,
                                                  ReservationBusinessHourEntity businessHour,
                                                  LocalTime cellStart, LocalTime cellEnd) {
        boolean overnight = Boolean.TRUE.equals(businessHour.getEndsNextDay());
        long businessLength = SlotTimeValidator.durationMinutes(
                businessHour.getOpenTime(), businessHour.getCloseTime(), overnight);
        long startOffset = Duration.between(businessHour.getOpenTime(), cellStart).toMinutes();
        if (overnight && cellStart.isBefore(businessHour.getOpenTime())) {
            startOffset += Duration.ofDays(1).toMinutes();
        }
        return startOffset >= 0 && startOffset + CELL_MINUTES <= businessLength;
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

        /** チャンク tx コミット成功後に、そのチャンクの件数を累計へ合算する。 */
        private void add(Counts other) {
            this.generated += other.generated;
            this.skippedExisting += other.skippedExisting;
            this.skippedClosedDay += other.skippedClosedDay;
            this.skippedOutsideHours += other.skippedOutsideHours;
        }
    }
}
