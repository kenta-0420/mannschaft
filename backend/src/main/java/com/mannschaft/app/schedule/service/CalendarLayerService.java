package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CalendarColorSource;
import com.mannschaft.app.schedule.dto.CalendarLayerResponse;
import com.mannschaft.app.schedule.dto.CalendarLayerUpdateRequest;
import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * カレンダーレイヤー設定サービス（F03.19 §4.3〜4.5）。
 *
 * <h2>認可</h2>
 * <p>本サービスは<b>常に呼び出し本人の設定だけ</b>を読み書きする。{@code userId} は
 * Controller が {@code SecurityUtils.getCurrentUserId()} から渡す認証主体であり、
 * リクエストボディ・パス・クエリのいずれからも受け取らない（§10.5 IDOR 防止）。</p>
 *
 * <p>所属判定は {@link AccessControlService#findAffiliatedScopeIds}（{@code user_roles} ∪
 * {@code memberships} の共通窓口）に委譲する【R3】。<b>本サービス専用の所属判定を書き起こさない。</b>
 * これにより「{@code GET /me/teams} の一覧には出るのに色を設定すると 403」という
 * 集合のズレを構造的に排除する（AC-10b2）。非所属スコープは存在／非存在を区別せず
 * 一律 {@link ScheduleErrorCode#CALENDAR_LAYER_NOT_MEMBER}（403）とし、ID 総当たり探索を防ぐ。</p>
 */
@Service
@RequiredArgsConstructor
public class CalendarLayerService {

    /** PERSONAL レイヤーの scopeId センチネル（DB・API・URL・FE キーで統一。R7）。 */
    public static final long PERSONAL_SCOPE_ID = 0L;

    /** PERSONAL レイヤー名の i18n キー（BE は日本語を返さない。§4.3.2 の注記）。 */
    public static final String PERSONAL_SCOPE_NAME_KEY = "schedule.calendar.layer.personal";

    /** 1ユーザーあたりの設定行数の上限（§10.1 / R17。サービス層で担保する）。 */
    public static final long MAX_LAYER_SETTINGS_PER_USER = 1000L;

    private static final String SCOPE_PERSONAL = "PERSONAL";
    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /** {@code #RRGGBB}（大小文字許容）。保存時は大文字へ正規化する。 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final UserCalendarLayerSettingRepository repository;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;

    /**
     * {@link #updateLayer} のデッドロック限定リトライで使う。1回の再試行ごとに
     * 新しいトランザクションを開始する必要があり、{@code @Transactional} の自己呼び出しは
     * プロキシを経由せず効かないため、明示的に {@code TransactionTemplate} を使う
     * （{@code ReservationGroupService} と同じ流儀）。
     */
    private final TransactionTemplate transactionTemplate;

    // ------------------------------------------------------------------
    // §4.3 GET /me/calendar-layers
    // ------------------------------------------------------------------

    /**
     * 本人のレイヤー一覧（所属スコープ ＋ 解決済み色 ＋ 表示可否）を返す。
     *
     * <p>並び順は PERSONAL → ORGANIZATION（scopeId 昇順）→ TEAM（scopeId 昇順）で安定させる
     * （予定の有無に依存しない・AC-04）。設定行が無いレイヤーは自動色（§3.3）で埋まる（P1）。
     * ページングしない（レイヤーは全部見えていること自体が要件・§4.2）。</p>
     */
    @Transactional(readOnly = true)
    public List<CalendarLayerResponse> listLayers(Long userId) {
        // 設定行の取得は常に本人の user_id 限定（他人の設定へ到達する経路を作らない）。
        Map<String, UserCalendarLayerSettingEntity> settings = new HashMap<>();
        for (UserCalendarLayerSettingEntity s : repository.findByUserId(userId)) {
            settings.put(settingKey(s.getScopeType(), s.getScopeId()), s);
        }

        // 所属列挙は AccessControlService の共通窓口（= /me/teams・/me/organizations と同じ 2 系統の和集合）。
        List<Long> orgIds = sortedAsc(accessControlService.findAffiliatedScopeIds(userId, SCOPE_ORGANIZATION));
        List<Long> teamIds = sortedAsc(accessControlService.findAffiliatedScopeIds(userId, SCOPE_TEAM));

        // 名前・アイコンはバッチ解決（N+1 を作らない）。
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);
        Map<Long, String> orgIcons = nameResolverService.resolveOrganizationIconUrls(orgIds);
        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
        Map<Long, String> teamIcons = nameResolverService.resolveTeamIconUrls(teamIds);

        List<CalendarLayerResponse> layers = new ArrayList<>();
        layers.add(toResponse(SCOPE_PERSONAL, PERSONAL_SCOPE_ID, SCOPE_PERSONAL, PERSONAL_SCOPE_NAME_KEY,
                null, settings.get(settingKey(SCOPE_PERSONAL, PERSONAL_SCOPE_ID))));

        for (Long orgId : orgIds) {
            String name = orgNames.get(orgId);
            if (name == null) {
                // 名前が解決できない（削除済み等）スコープは一覧に出さない。
                continue;
            }
            layers.add(toResponse(SCOPE_ORGANIZATION, orgId, name, null, orgIcons.get(orgId),
                    settings.get(settingKey(SCOPE_ORGANIZATION, orgId))));
        }
        for (Long teamId : teamIds) {
            String name = teamNames.get(teamId);
            if (name == null) {
                continue;
            }
            layers.add(toResponse(SCOPE_TEAM, teamId, name, null, teamIcons.get(teamId),
                    settings.get(settingKey(SCOPE_TEAM, teamId))));
        }
        return layers;
    }

    /**
     * 本人が<b>明示的に色を設定した</b>レイヤーだけを {@code "{scopeType}:{scopeId}"} → 色 の Map で返す
     * （F03.19 §4.7・優先1 の解決に使う）。
     *
     * <p>{@code /my/calendar} と個人予定一覧の色解決は、この <b>1 回</b>の読み取り結果を
     * メモリ上で引く（ループ内で Repository を呼ばない）。色未設定（{@code color IS NULL}）の行は
     * 自動色にフォールバックすべきなので Map に載せない — 「キーが無い＝優先1 は不成立」で
     * 呼び出し側の分岐を一段減らす。</p>
     *
     * <p>設定の読み取り経路を本メソッドに集約することで、レイヤー設定を読むクエリが
     * サービスごとに増殖するのを防ぐ（W1-b で作った窓口をそのまま使う）。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, String> findUserLayerColors(Long userId) {
        Map<String, String> colors = new HashMap<>();
        if (userId == null) {
            return colors;
        }
        for (UserCalendarLayerSettingEntity s : repository.findByUserId(userId)) {
            if (s.getColor() != null) {
                colors.put(settingKey(s.getScopeType(), s.getScopeId()), s.getColor());
            }
        }
        return colors;
    }

    // ------------------------------------------------------------------
    // §4.4 PATCH /me/calendar-layers/{scopeType}/{scopeId}
    // ------------------------------------------------------------------

    /**
     * レイヤー設定を<b>部分更新</b>する（R2）。
     *
     * <p>{@code request} の各項目の {@code null} は「変更しない」を意味し、送られなかった項目は
     * 現在値を維持する（色を変えただけで {@code hidden} が巻き戻る P2 違反を作らない）。
     * 設定行がまだ無い場合は作成し、送られなかった項目は既定値
     * （{@code color=null}＝自動色 / {@code hidden=false}）で埋める。</p>
     *
     * <h3>直前の修繕（46988b57d）が生んだ2つの欠陥とその根治</h3>
     *
     * <p><b>欠陥①（デッドロック）</b>: {@code countByUserIdForUpdate}（{@code SELECT COUNT(*) ...
     * WHERE user_id = ? FOR UPDATE}）は、その user_id の行がまだ0件のとき「空範囲」に
     * ギャップロックを張る。InnoDB のギャップロックは<b>互いに互換</b>（排他しない）ため、
     * 異なるスコープへの2並行 PATCH は両方ともこの空ギャップのロックを取得でき、両方が
     * 件数チェック（0 &lt; 1000）を通過してしまう。その直後、それぞれの
     * {@code insertIfAbsent}（INSERT）が張る insert intention lock が<b>相手のギャップロックに
     * 阻まれ</b>、循環待ちでデッドロックする。ギャップロック方式は「複数トランザクションが
     * 同時に同じ空範囲を『空いている』と見なせてしまう」性質そのものが原因であり、
     * ロックの粒度をいくら調整しても解消しない族の欠陥である
     * （根治は下記のリトライ・{@link #updateLayer(Long, String, Long, CalendarLayerUpdateRequest)}）。</p>
     *
     * <p><b>欠陥②（誤 409）</b>: 旧実装は行の有無をロック取得<b>前</b>の通常 SELECT
     * （スナップショット読み）で判定していた。本番 MySQL は REPEATABLE-READ であり、
     * 通常の SELECT はトランザクション冒頭のスナップショットを見続けるため、上限付近
     * （例: 999件）で同一スコープへ2並行 PATCH が来ると、後着は「先着がその後コミットした行」を
     * 見られないまま新規作成の分岐へ入り、{@code countByUserIdForUpdate} で待たされたのち
     * 件数1000を見て {@code CALENDAR_LAYER_LIMIT_EXCEEDED}（409）を返す。しかし対象行は
     * 先着によって<b>既に存在する</b>ので、これは §10.1「既存行の更新は行数上限に関係なく
     * 成功する」への違反である。根治は「ロック区間に入ってから対象キーを現在読み取りで
     * 再確認する」こと — {@link UserCalendarLayerSettingRepository#findForUpdateByUserIdAndScopeTypeAndScopeId}
     * は {@code FOR UPDATE} なので最新のコミット済みバージョンを読め、行が存在すれば
     * 上限判定を一切行わず既存行更新経路へ分岐できる。</p>
     */
    public CalendarLayerResponse updateLayer(Long userId, String scopeType, Long scopeId,
                                             CalendarLayerUpdateRequest request) {
        // 認可ガードはラムダの外・public 入口の冒頭に置く（本メソッドがそれ）。理由は3つ:
        // (a) 本プロジェクトの方針として認可ガードは public 入口へ置く
        //     （backend/.claudecode.md・AuthzControllerGuardArchTest 系の番人が前提とする配置）。
        // (b) ここより後ろは transactionTemplate.execute(status -> ...) のラムダ経由になり、
        //     ArchUnit の静的呼び出しグラフはラムダ境界を越えて到達判定できない。認可チェックを
        //     ラムダの内側（旧 updateLayerBody 冒頭）に置いていたため、コントローラから
        //     checkAffiliation への到達が検出できず AuthzControllerGuardArchTest が
        //     誤って「認可シグナル無し」を報告していた（実行時の認可自体は生きていたが、
        //     番人の指摘は静的検査として正当）。
        // (c) 所属検証はデッドロック時の再試行対象にすべき処理ではない
        //     （所属関係はトランザクション内で変化しないため、リトライのたびにやり直す意味がない）。
        String type = validateScope(scopeType, scopeId);
        long id = normalizedScopeId(type, scopeId);
        checkAffiliation(userId, type, id);

        // デッドロック（欠陥①）はロックの粒度をいくら絞っても原理上は残る
        // （ギャップロックは互換なので、複数トランザクションが同じ空範囲を同時に確保しうる）。
        // よって「デッドロックを起こさない」のではなく「デッドロックしたら新しいトランザクションで
        // 取り直す」方針を採る。
        //
        // なぜ @Retryable + @Transactional を同一メソッドへ重ねる案（quickmemo の前例）を
        // 採らなかったか: その前例は「呼び出し元が別クラス」なので Spring AOP プロキシを必ず経由するが、
        // 本メソッドは同一クラス内の updateLayerBody を呼ぶ自己呼び出し（{@code this.xxx()}）になる。
        // 自己呼び出しは両アノテーションの動的プロキシを一切経由しないため、@Retryable はおろか
        // @Transactional 自体も効かなくなる（Spring の既知の落とし穴）。よってここでは
        // TransactionTemplate（{@code ReservationGroupService} と同じ流儀）による明示ループで
        // 「例外を検知 → 新しいトランザクションで取り直す」を実装する。
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return transactionTemplate.execute(status ->
                        updateLayerBody(userId, type, id, request));
            } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                if (attempt >= MAX_UPDATE_LAYER_ATTEMPTS) {
                    // リトライを尽くしても解消しない場合は握りつぶさずそのまま上げる（対処療法禁止）。
                    throw e;
                }
                // 短いバックオフを挟んでから、新しいトランザクションで取り直す。
                sleepBeforeRetry(attempt);
            }
        }
    }

    /** {@link #updateLayer} のリトライ上限（デッドロック時のみ）。 */
    private static final int MAX_UPDATE_LAYER_ATTEMPTS = 3;

    /** デッドロック再試行前の短いバックオフ（ミリ秒。指数的に伸ばす）。 */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(50L * (1L << (attempt - 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@link #updateLayer} の本体（1トランザクション分）。デッドロック時は呼び出し元が
     * 新しいトランザクションでリトライできるよう、ここでは例外を握りつぶさずそのまま投げる
     * （対処療法禁止・CLAUDE.md）。
     *
     * <p>スコープの検証・正規化・所属検証は呼び出し元 {@link #updateLayer} が
     * ラムダの外（public 入口）で済ませている。ここでは正規化済みの {@code type}/{@code id}
     * をそのまま使い、再検証はしない（理由は {@link #updateLayer} のコメント参照）。</p>
     */
    private CalendarLayerResponse updateLayerBody(Long userId, String type, long id,
                                             CalendarLayerUpdateRequest request) {
        CalendarLayerUpdateRequest body =
                request != null ? request : new CalendarLayerUpdateRequest(null, null);
        String normalizedColor = validateAndNormalizeColor(body.color());

        // 初期チェックは通常の SELECT（スナップショット読み）— ロック区間に入らない高速経路の最適化に過ぎない。
        // ここで存在しなくても、ロック区間に入ってから必ず現在読み取りで再確認する（欠陥②対策）。
        Optional<UserCalendarLayerSettingEntity> existing =
                repository.findByUserIdAndScopeTypeAndScopeId(userId, type, id);

        UserCalendarLayerSettingEntity entity;
        if (existing.isPresent()) {
            // 既存行の更新は行数上限に関係なく成功する（§10.1）。
            entity = existing.get();
        } else {
            // ロック区間に入ってから対象キーを現在読み取りで再確認する（欠陥②の根治）。
            // 通常の findBy...（スナップショット読み）では、REPEATABLE-READ の下で並行トランザクションの
            // コミットが見えないため、「先着が既に作った行」を見落として誤って新規作成・上限判定に進んでしまう。
            // FOR UPDATE は現在読み取りなので、ここで存在が確定すれば上限判定は一切行わず既存行更新へ回す。
            Optional<UserCalendarLayerSettingEntity> lockedExisting =
                    repository.findForUpdateByUserIdAndScopeTypeAndScopeId(userId, type, id);
            if (lockedExisting.isPresent()) {
                entity = lockedExisting.get();
            } else {
                // ここに来るのは「本当にまだ行が無い」ときだけ。件数チェックと新規行作成を
                // 「ユーザー単位」で直列化する（並行 PATCH による上限すり抜け対策）。
                //
                // 採った手段: countByUserId ではなく countByUserIdForUpdate（SELECT COUNT(*) ... FOR UPDATE）。
                // 理由は本番 MySQL が REPEATABLE-READ であること — 通常の SELECT COUNT はスナップショット読みなので、
                // 同一ユーザーが異なるスコープへ同時に PATCH すると両トランザクションが揃って「999 件（＝作ってよい）」
                // という同じスナップショットを見てしまい、両方が新規行を作って上限を超える（uk_user_calendar_layer は
                // スコープごとに別キーなのでユニーク制約では検出できない）。
                //
                // ただしこの FOR UPDATE 自体が空範囲へのギャップロックを張るため、異なるスコープへの2並行
                // PATCH が同時にこの空ギャップを確保でき、直後の insertIfAbsent 同士がデッドロックしうる
                // （欠陥①・クラスコメント参照）。ギャップロックは互換なので、ロックの粒度をここでどう絞っても
                // 原理的に解消できない。よってここでは塞ぐことを諦め、デッドロックが起きたら
                // updateLayer が新しいトランザクションでリトライすることで最終的な整合性を担保する。
                if (repository.countByUserIdForUpdate(userId) >= MAX_LAYER_SETTINGS_PER_USER) {
                    throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_LIMIT_EXCEEDED);
                }
                entity = createRowAtomically(userId, type, id);
            }
        }

        // 部分更新: null は「変更しない」。
        if (normalizedColor != null) {
            entity.setColor(normalizedColor);
        }
        if (body.hidden() != null) {
            entity.setHidden(body.hidden());
        }
        if (entity.getHidden() == null) {
            entity.setHidden(false);
        }

        UserCalendarLayerSettingEntity saved = repository.save(entity);
        return toResponse(type, id, scopeDisplayName(type, id), scopeNameKey(type),
                scopeIconUrl(type, id), saved);
    }

    /**
     * 設定行がまだ無いときの<b>原子的な行作成</b>（並行 PATCH で 500 にしないための要）。
     *
     * <p>「{@code findBy...} が空 → 新規 Entity を {@code save}」は検査と書き込みの間に隙間があり、
     * 設定行がまだ無い同一レイヤーへ PATCH が並行して 2 件来ると両方が新規行を作ろうとして、
     * 後着が {@code uk_user_calendar_layer}（{@code user_id, scope_type, scope_id}）違反で
     * 500 を返していた。PATCH の再送や二重操作で普通に起き、
     * <b>upsert・冪等という API 契約を破る</b>。</p>
     *
     * <p><b>採った手段は {@code INSERT IGNORE}</b>
     * （{@link UserCalendarLayerSettingRepository#insertIfAbsent}）である。
     * 例外捕捉＋リトライ（{@code save} で {@code DataIntegrityViolationException} を捕まえて取り直す）は
     * この場では成立しない — 制約違反が例外化された時点で<b>現在のトランザクションが
     * rollback-only になり</b>、同じトランザクション内で取り直して更新に回せないからである
     * （同じ結論に {@code AdCampaignDeliveryClaimRepository} が先に到達している）。
     * 別 Bean の {@code REQUIRES_NEW} でリトライする手もあるが、
     * PATCH 1 本のために新しいトランザクション境界を増やすより、
     * 同一ドメインの兄弟（{@code UserCalendarSyncSettingRepository#upsert}）と同じ
     * DB 側の upsert に寄せるほうが構造が薄い。</p>
     *
     * <p>{@code INSERT IGNORE} が 0 件を返す＝並行 PATCH に先を越された場合は、
     * 相手が作った行を取り直してそのまま部分更新に回す。どちらが先着でも最終状態は同じになる。</p>
     *
     * <p><b>取り直しはロック付き読み取り</b>（{@code SELECT ... FOR UPDATE}）でなければならない。
     * 本番 MySQL の分離レベルは {@code REPEATABLE-READ} であり、通常の SELECT は
     * トランザクション冒頭に張ったスナップショットを見続けるため、
     * {@code INSERT IGNORE} を挟んでも<b>先着がコミットした行は見えない</b>。
     * 通常の {@code findBy...} で取り直すと必ず {@code IllegalStateException}（＝ 500）になり、
     * 塞いだはずの欠陥がそのまま残る。ロック付き読み取りは InnoDB では現在読み取りとなり、
     * 最新のコミット済みバージョンを読む。
     * 詳細は {@link UserCalendarLayerSettingRepository#findForUpdateByUserIdAndScopeTypeAndScopeId}。</p>
     */
    private UserCalendarLayerSettingEntity createRowAtomically(Long userId, String type, long id) {
        UUID newId = UuidV7.generate();
        int inserted = repository.insertIfAbsent(toBinary16(newId), userId, type, id);
        if (inserted > 0) {
            // 自分が作った行。採番済み ID を載せておけば以降の save は UPDATE になる。
            UserCalendarLayerSettingEntity created = UserCalendarLayerSettingEntity.builder()
                    .userId(userId)
                    .scopeType(type)
                    .scopeId(id)
                    .color(null)
                    .hidden(false)
                    .build();
            created.setId(newId);
            return created;
        }
        // 並行 PATCH に先を越された。相手の行を「ロック付き読み取り」で取り直して更新に回す（冪等）。
        // 通常の findBy... では駄目である: 本番 MySQL は REPEATABLE-READ で、
        // 通常の SELECT はトランザクション冒頭のスナップショットを見続けるため、
        // 先着がコミットした行が最後まで見えず IllegalStateException（＝500）に落ちる。
        // FOR UPDATE は InnoDB では現在読み取りになり、最新のコミット済み行を確実に読める。
        return repository.findForUpdateByUserIdAndScopeTypeAndScopeId(userId, type, id)
                .orElseThrow(() -> new IllegalStateException(
                        "INSERT IGNORE が 0 件を返したのに行が見つからない: userId=" + userId
                                + ", scopeType=" + type + ", scopeId=" + id));
    }

    /** UUID を {@code BINARY(16)} のバイト列へ変換する（DDL の id 列に合わせる）。 */
    private static byte[] toBinary16(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    // ------------------------------------------------------------------
    // §4.5 DELETE /me/calendar-layers/{scopeType}/{scopeId}
    // ------------------------------------------------------------------

    /**
     * レイヤー設定を物理削除して自動色・{@code hidden=false} に戻す。
     *
     * <p>行が存在しなくても例外を投げない（冪等・{@code 204}。404 は返さない）。</p>
     */
    @Transactional
    public void deleteLayer(Long userId, String scopeType, Long scopeId) {
        String type = validateScope(scopeType, scopeId);
        long id = normalizedScopeId(type, scopeId);
        checkAffiliation(userId, type, id);

        repository.deleteByUserIdAndScopeTypeAndScopeId(userId, type, id);
    }

    // ------------------------------------------------------------------
    // 認可・バリデーション
    // ------------------------------------------------------------------

    /**
     * 呼び出し本人が当該スコープに所属していることを検証する。
     *
     * <p>PERSONAL は本人自身のレイヤーであり所属という概念を持たないため検証不要
     * （scopeId=0 であることは {@link #validateScope} が保証済み）。</p>
     */
    private void checkAffiliation(Long userId, String scopeType, long scopeId) {
        if (SCOPE_PERSONAL.equals(scopeType)) {
            return;
        }
        Set<Long> affiliated = accessControlService.findAffiliatedScopeIds(userId, scopeType);
        if (affiliated == null || !affiliated.contains(scopeId)) {
            // 非所属も存在しないIDも同一コード（存在秘匿・ID 総当たり探索の防止）。
            throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_NOT_MEMBER);
        }
    }

    /** scopeType / scopeId の形を検証し、正規化した scopeType を返す。 */
    private String validateScope(String scopeType, Long scopeId) {
        if (scopeType == null) {
            throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
        }
        switch (scopeType) {
            case SCOPE_PERSONAL -> {
                if (scopeId == null || scopeId != PERSONAL_SCOPE_ID) {
                    // PERSONAL の scopeId は全境界で 0 固定（R7）。
                    throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
                }
                return SCOPE_PERSONAL;
            }
            case SCOPE_TEAM, SCOPE_ORGANIZATION -> {
                if (scopeId == null || scopeId <= 0L) {
                    throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
                }
                return scopeType;
            }
            default -> throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
        }
    }

    private long normalizedScopeId(String scopeType, Long scopeId) {
        return SCOPE_PERSONAL.equals(scopeType) ? PERSONAL_SCOPE_ID : scopeId;
    }

    /**
     * 色の形式（{@code #RRGGBB}）を検証し大文字へ正規化する。
     *
     * @return 正規化済みの色。{@code null}（変更しない）ならそのまま {@code null}
     */
    private String validateAndNormalizeColor(String color) {
        if (color == null) {
            return null;
        }
        if (!COLOR_PATTERN.matcher(color).matches()) {
            throw new BusinessException(ScheduleErrorCode.CALENDAR_LAYER_INVALID_COLOR);
        }
        return color.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 組み立て
    // ------------------------------------------------------------------

    private static String settingKey(String scopeType, Long scopeId) {
        return scopeType + ":" + (scopeId == null ? PERSONAL_SCOPE_ID : scopeId);
    }

    private static List<Long> sortedAsc(Set<Long> ids) {
        List<Long> sorted = new ArrayList<>(ids == null ? Set.<Long>of() : ids);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    private static String scopeNameKey(String scopeType) {
        return SCOPE_PERSONAL.equals(scopeType) ? PERSONAL_SCOPE_NAME_KEY : null;
    }

    /**
     * 表示名を解決する。PERSONAL は BE が日本語を持たない（FE が {@code scopeNameKey} を翻訳する）ため
     * 種別名をそのまま返す。名前が解決できない場合も種別名で埋める（PATCH 応答を欠かさない）。
     */
    private String scopeDisplayName(String scopeType, long scopeId) {
        if (SCOPE_PERSONAL.equals(scopeType)) {
            return SCOPE_PERSONAL;
        }
        String name = nameResolverService.resolveScopeName(scopeType, scopeId);
        return name != null ? name : scopeType;
    }

    private String scopeIconUrl(String scopeType, long scopeId) {
        if (SCOPE_PERSONAL.equals(scopeType)) {
            return null;
        }
        return nameResolverService.resolveIconUrl(scopeType, scopeId);
    }

    /**
     * レイヤー1件の応答を組み立てる。設定行の色が {@code null}（未設定）なら自動色へフォールバックし、
     * {@code colorSource} も {@code LAYER_AUTO} になる（§3.4 の優先1 or 4）。
     */
    private CalendarLayerResponse toResponse(String scopeType, long scopeId, String scopeName,
                                             String scopeNameKey, String scopeIconUrl,
                                             UserCalendarLayerSettingEntity setting) {
        String userColor = setting != null ? setting.getColor() : null;
        boolean hidden = setting != null && Boolean.TRUE.equals(setting.getHidden());

        String color = userColor != null ? userColor : CalendarLayerAutoColor.resolve(scopeType, scopeId);
        CalendarColorSource source = userColor != null
                ? CalendarColorSource.LAYER_USER
                : CalendarColorSource.LAYER_AUTO;

        return new CalendarLayerResponse(scopeType, scopeId, scopeName, scopeNameKey,
                scopeIconUrl, color, source, hidden);
    }
}
