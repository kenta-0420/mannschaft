package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.security.AuthorizedInService;
import org.junit.jupiter.api.Tag;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認可をトランザクションの外のファサードへ出した型（CMP-260923-0954 / plan4 の AC-8・AC-15・K7）の<b>横断の番人</b>（W6b）。
 *
 * <p>W1〜W6a で「Controller → 非 tx の Facade（認可）→ tx 本体（認可なし）」に作り替えた Controller メソッドを、
 * 1 つの登録表（{@link #ENTRIES}: Controller#メソッド → Facade）と、Facade ごとの tx 本体の表（{@link #TX_BODIES}）に集め、
 * 共通の規則を一括で検査する。波ごとに型がばらばらだった既存の ArchTest の共通部をここへ寄せ、各テストには
 * その波に固有の項目だけを残している（ep_tables_w6.md §3.5・「殿の判断（2026-10-03）」W6b）。</p>
 * <ol>
 *   <li>R1: 登録メソッドは指定の Facade を呼び、tx 本体（登録された tx 本体と、同じドメインの {@code *Service}）を
 *       直接呼ばない。指定以外の登録 Facade も呼ばない。</li>
 *   <li>R2: Facade にクラス・メソッドとも {@code @Transactional}（Spring・Jakarta）が無い。</li>
 *   <li>R3: Facade の public メソッドはすべて {@code ScopeConcealingAccessGate} か {@code AccessControlService} へ届く
 *       （直接か、同クラスのメソッド・ラムダ経由で深さ 2 まで）。</li>
 *   <li>R4: tx 本体は認可クラスに依存しない。{@link TxMode#CLASS} はクラスごと、{@link TxMode#METHOD}（他の EP の
 *       旧来の認可が同じクラスに残る recruitment）は Facade から呼ばれるメソッドだけを、同クラス内を深さ 3 までたどって検査する。
 *       登録メソッドごとに tx 本体の呼び出しが 1 本以上あること、Facade が同ドメインの未登録 {@code *Service} を呼ばないことも固定する。</li>
 *   <li>R5: 登録メソッドに {@code @AuthorizedInService}・{@code @SelfScopedEndpoint} が付いていない
 *       （印だけで AuthzControllerGuard を通る偽陽性を残さない）。</li>
 *   <li>R6（AC-8）: Facade と tx 本体で、{@code AccessControlService} の throw 形（{@code check*}）の直接呼び出しを禁じる。
 *       越境を 403 で返すと実在が割れる（存在オラクル）ため。正当な例外は {@link #THROW_FORM_ALLOWLIST}（理由必須）。
 *       tx 本体は R4 が認可クラスへの到達そのものを禁じているので、R6 が新たに縛るのは Facade 側である。</li>
 *   <li>R7: Facade の名前は {@code *Facade}（{@code *AccessService} / {@code *AccessGuard} / {@code *AccessGate} にすると
 *       呼んだだけで認可シグナル扱いになり、Facade 内の認可漏れを AuthzControllerGuard が見逃す）。</li>
 *   <li>完全性: 「いずれかの登録 Facade を呼ぶ Controller メソッド」を全 Controller から自動で列挙し、登録表にも
 *       {@link #UNREGISTERED_FACADE_CALLERS}（理由必須）にも無ければ赤（登録漏れで番人が空振りしない）。
 *       逆に登録表・許可リスト・除外表の項目が実在しなければ赤（リネームで空振りしない）。</li>
 * </ol>
 *
 * <p>凍結（FreezingArchRule）は使わない。規則の検出力は {@code AuthzTxFacadeRegistryScanningLogicTest} が
 * 合成クラスの陽性・陰性対照で示す。</p>
 */
@DisplayName("認可ファサード型（W1〜W6a）の横断の番人（登録表）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class AuthzTxFacadeRegistryArchTest {

    static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";
    static final String GATE = "com.mannschaft.app.common.ScopeConcealingAccessGate";

    private static final String SHIFT = "com.mannschaft.app.shift.";
    private static final String REC = "com.mannschaft.app.recruitment.";
    private static final String RSV = "com.mannschaft.app.reservation.";
    private static final String CNF = "com.mannschaft.app.notification.confirmable.";

    /** 登録表の 1 行（Controller#メソッド → 呼ぶべき Facade）。 */
    record Entry(String wave, String controller, String method, String facade) {
        String key() {
            return controller + "#" + method;
        }
    }

    /** tx 本体の検査の粒度。 */
    enum TxMode {
        /** クラスごと認可クラスに依存しない（認可用の private・ラムダも残さない）。 */
        CLASS,
        /** Facade から呼ばれるメソッドだけが認可クラスに届かない（他 EP の旧来の認可が同じクラスに残る）。 */
        METHOD
    }

    record TxBody(String fqn, TxMode mode) {
    }

    /** 番人の入力一式（本番の表と、対照テストの合成の表を同じ規則に通すため）。 */
    record Rules(
            List<Entry> entries,
            Map<String, List<TxBody>> txBodiesByFacade,
            Set<String> authzClasses,
            String throwFormOwner,
            Map<String, String> throwFormAllowlist,
            Map<String, String> unregisteredFacadeCallers) {

        Set<String> facades() {
            return new LinkedHashSet<>(txBodiesByFacade.keySet());
        }

        Set<String> txBodyClasses() {
            return txBodiesByFacade.values().stream().flatMap(List::stream).map(TxBody::fqn)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Rules withThrowFormAllowlist(Map<String, String> allowlist) {
            return new Rules(entries, txBodiesByFacade, authzClasses, throwFormOwner, allowlist,
                    unregisteredFacadeCallers);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 登録表
    // ═════════════════════════════════════════════════════════════════════

    static final List<Entry> ENTRIES = entries();

    private static List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        // W1: シフト希望・ポジション（listMyRequests は @SelfScopedEndpoint で tx 本体の直呼びを許すので対象外）
        add(list, "W1", SHIFT + "controller.ShiftRequestController", SHIFT + "service.ShiftRequestFacade",
                "listRequests", "submitRequest", "updateRequest", "deleteRequest", "getRequestSummary");
        add(list, "W1", SHIFT + "controller.ShiftPositionController", SHIFT + "service.ShiftPositionFacade",
                "listPositions", "createPosition", "updatePosition", "deletePosition");
        // W2: 交代・変更依頼・自動割当
        add(list, "W2", SHIFT + "controller.ShiftSwapController", SHIFT + "service.ShiftSwapFacade",
                "listSwapRequests", "createSwapRequest", "acceptSwapRequest", "resolveSwapRequest", "cancelSwapRequest");
        add(list, "W2", SHIFT + "controller.ShiftChangeRequestController", SHIFT + "service.ShiftChangeRequestFacade",
                "createChangeRequest", "listChangeRequests", "getChangeRequest", "reviewChangeRequest",
                "withdrawChangeRequest");
        add(list, "W2", SHIFT + "controller.ShiftAutoAssignController", SHIFT + "service.ShiftAutoAssignFacade",
                "runAutoAssign", "confirmAutoAssign", "revokeAutoAssign", "getAssignmentRuns", "getAssignmentRunDetail",
                "confirmVisualReview");
        // W3a: 予約詳細
        add(list, "W3a", RSV + "controller.TeamReservationController", RSV + "service.ReservationDetailFacade",
                "getReservation");
        // W3b: 確認通知の受信者ページ（Team・Org）
        add(list, "W3b", CNF + "controller.TeamConfirmableNotificationController",
                CNF + "service.ConfirmableNotificationRecipientPageFacade", "getRecipientsPage");
        add(list, "W3b", CNF + "controller.OrgConfirmableNotificationController",
                CNF + "service.ConfirmableNotificationRecipientPageFacade", "getRecipientsPage");
        // W4: 金銭・制裁
        add(list, "W4", REC + "controller.RecruitmentCancellationRecordController", REC + "service.RecruitmentMoneyFacade",
                "waive");
        add(list, "W4", REC + "controller.RecruitmentPenaltyController", REC + "service.RecruitmentMoneyFacade",
                "liftPenalty");
        add(list, "W4", REC + "controller.RecruitmentListingController", REC + "service.RecruitmentMoneyFacade",
                "confirmApplication");
        add(list, "W4", REC + "controller.CancellationPolicyController", REC + "service.RecruitmentMoneyFacade",
                "get", "update", "archive");
        // W5: 募集の書込系・参加者管理・テンプレート
        add(list, "W5", REC + "controller.RecruitmentListingController", REC + "service.RecruitmentListingFacade",
                "update", "publish", "cancel", "archive", "getDistributionTargets", "setDistributionTargets");
        add(list, "W5", REC + "controller.RecruitmentApplicationController", REC + "service.RecruitmentListingFacade",
                "listParticipants", "markAttended");
        add(list, "W5", REC + "controller.RecruitmentTemplateController", REC + "service.RecruitmentListingFacade",
                "getTemplate", "updateTemplate", "archiveTemplate");
        // W5 周辺: W5 の 11 本には含めなかったが Facade 経由になった参加申込。同じ規則に通す
        // （募集詳細 GET は tx 本体に可視性判定が残るため除外表）
        add(list, "W5周辺", REC + "controller.RecruitmentApplicationController", REC + "service.RecruitmentListingFacade",
                "apply");
        // W6a: schedules・slots・remind・PDF
        add(list, "W6a", SHIFT + "controller.ShiftScheduleController", SHIFT + "service.ShiftScheduleFacade",
                "listSchedules", "getSchedule", "createSchedule", "updateSchedule", "deleteSchedule", "transitionStatus",
                "getScheduleSummary", "remindUnsubmitted", "duplicateSchedule");
        add(list, "W6a", SHIFT + "controller.ShiftSlotController", SHIFT + "service.ShiftSlotFacade",
                "listSlots", "createSlot", "bulkCreateSlots", "updateSlot", "deleteSlot", "patchSlotAssignments");
        add(list, "W6a", SHIFT + "controller.ShiftPdfController", SHIFT + "service.ShiftPdfFacade", "exportPdf");
        return List.copyOf(list);
    }

    private static void add(List<Entry> list, String wave, String controller, String facade, String... methods) {
        for (String m : methods) {
            list.add(new Entry(wave, controller, m, facade));
        }
    }

    /** Facade → tx 本体（Facade が認可の後に呼ぶ、認可を持たない本体）。 */
    static final Map<String, List<TxBody>> TX_BODIES = txBodies();

    private static Map<String, List<TxBody>> txBodies() {
        Map<String, List<TxBody>> map = new LinkedHashMap<>();
        map.put(SHIFT + "service.ShiftRequestFacade", List.of(cls(SHIFT + "service.ShiftRequestService")));
        map.put(SHIFT + "service.ShiftPositionFacade", List.of(cls(SHIFT + "service.ShiftPositionService")));
        map.put(SHIFT + "service.ShiftSwapFacade", List.of(cls(SHIFT + "service.ShiftSwapService")));
        map.put(SHIFT + "service.ShiftChangeRequestFacade", List.of(cls(SHIFT + "service.ShiftChangeRequestService")));
        map.put(SHIFT + "service.ShiftAutoAssignFacade", List.of(cls(SHIFT + "service.ShiftAutoAssignService")));
        map.put(RSV + "service.ReservationDetailFacade", List.of(cls(RSV + "service.ReservationService")));
        // ConfirmableNotificationService は Facade から呼ばれないが、W3b で認可を外した tx 本体としてクラスごと固定する
        map.put(CNF + "service.ConfirmableNotificationRecipientPageFacade", List.of(
                cls(CNF + "service.ConfirmableNotificationQueryService"),
                cls(CNF + "service.ConfirmableNotificationService")));
        map.put(REC + "service.RecruitmentMoneyFacade", List.of(
                cls(REC + "service.RecruitmentCancellationFeeWaiveService"),
                method(REC + "service.RecruitmentPenaltyService"),
                method(REC + "service.RecruitmentListingService"),
                method(REC + "service.RecruitmentCancellationPolicyService")));
        map.put(REC + "service.RecruitmentListingFacade", List.of(
                method(REC + "service.RecruitmentListingService"),
                method(REC + "service.RecruitmentParticipantService"),
                method(REC + "service.RecruitmentTemplateService")));
        map.put(SHIFT + "service.ShiftScheduleFacade", List.of(
                cls(SHIFT + "service.ShiftScheduleService"),
                cls(SHIFT + "service.ShiftPreferenceReminderBatchService")));
        map.put(SHIFT + "service.ShiftSlotFacade", List.of(cls(SHIFT + "service.ShiftSlotService")));
        map.put(SHIFT + "service.ShiftPdfFacade", List.of(
                cls(SHIFT + "service.ShiftScheduleService"),
                cls(SHIFT + "service.ShiftPdfService")));
        return map;
    }

    private static TxBody cls(String fqn) {
        return new TxBody(fqn, TxMode.CLASS);
    }

    private static TxBody method(String fqn) {
        return new TxBody(fqn, TxMode.METHOD);
    }

    /**
     * R6 の許可リスト: 「Facade FQN#メソッド名（ラムダは囲むメソッド名）」→ throw 形を直接呼んでよい理由。
     * 理由が空なら {@link #許可リストと除外表に理由がある()} が落ちる。使われなくなった項目も赤にする（陳腐化の防止）。
     */
    static final Map<String, String> THROW_FORM_ALLOWLIST = Map.of(
            SHIFT + "service.ShiftPositionFacade#checkTeamAdminAccess",
            "teamId 直接指定のポジション一覧・作成は隠蔽対象のリソース ID を持たず、是正前から 403 COMMON_002 のまま（AC-6）。"
                    + "SYSTEM_ADMIN を先に通したうえで checkAdminOrAbove を使う",
            SHIFT + "service.ShiftChangeRequestFacade#create",
            "非メンバーの SYSTEM_ADMIN は是正前どおり 403 COMMON_002 で拒否する（AC-5 改訂）。Gate は冒頭で SYSTEM_ADMIN を"
                    + "通してしまうため、SYSTEM_ADMIN のときだけ checkMembership で先に弾く",
            SHIFT + "service.ShiftScheduleFacade#createSchedule",
            "?teamId= 直接指定のスケジュール作成は隠蔽対象のリソース ID を持たず、越境・不在・権限不足とも 403 COMMON_002 のまま"
                    + "（ep_tables_w6.md §4-4 の S3）。SYSTEM_ADMIN を先に通したうえで checkAdminOrAbove を使う");

    /**
     * 登録 Facade を呼ぶが登録表に載せない Controller メソッド（「Controller FQN#メソッド名」→ 理由）。理由必須。
     */
    static final Map<String, String> UNREGISTERED_FACADE_CALLERS = Map.of(
            REC + "controller.RecruitmentListingController#get",
            "募集詳細 GET。Facade は DRAFT の閲覧者判定と個人札の隠蔽だけを持ち、非 DRAFT の可視性（F00）と管理者判定は"
                    + "tx 本体 RecruitmentListingService#getListing に残る（W5 の対象外＝認可の型を変えない EP）。"
                    + "R4 の「tx 本体は認可へ届かない」を満たさないため登録しない",
            REC + "controller.RecruitmentListingController#estimateCancellationFee",
            "キャンセル料試算。認可は GET 詳細と同じ RecruitmentListingFacade#getListing を先に呼んで済ませ、その後の"
                    + "findOrThrow・estimateFee は認可を持たない参照。W5 の対象外（試算の作り替えは別課題）で、"
                    + "tx 本体の直呼びが残るため R1 に通せない");

    static final Rules RULES = new Rules(ENTRIES, TX_BODIES, Set.of(ACCESS_CONTROL, GATE), ACCESS_CONTROL,
            THROW_FORM_ALLOWLIST, UNREGISTERED_FACADE_CALLERS);

    private final JavaClasses classes = ProductionClasses.get();

    // ═════════════════════════════════════════════════════════════════════
    // テスト
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("登録表の件数（波ごと）を固定する（足し忘れ・消し忘れを差分で見せる）")
    void 登録表の件数() {
        Map<String, Long> byWave = ENTRIES.stream()
                .collect(Collectors.groupingBy(Entry::wave, TreeMap::new, Collectors.counting()));
        assertThat(byWave).containsExactlyInAnyOrderEntriesOf(Map.of(
                "W1", 9L, "W2", 16L, "W3a", 1L, "W3b", 2L, "W4", 6L, "W5", 11L, "W5周辺", 1L, "W6a", 16L));
        assertThat(ENTRIES).hasSize(62);
        assertThat(ENTRIES.stream().map(Entry::key).distinct().count()).as("登録表に重複が無い").isEqualTo(62);
        assertThat(ENTRIES.stream().map(Entry::facade).collect(Collectors.toSet()))
                .as("登録表の Facade と tx 本体の表の Facade が一致する").isEqualTo(TX_BODIES.keySet());
    }

    @Test
    @DisplayName("登録表・tx 本体・許可リスト・除外表の項目が実在する（リネームで番人が空振りしない）")
    void 登録項目が実在する() {
        assertThat(registryTargetsExist(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("完全性: 登録 Facade を呼ぶ Controller メソッドはすべて登録表（または理由付きの除外表）にある")
    void 登録Facadeを呼ぶControllerメソッドはすべて登録されている() {
        assertThat(completeness(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("R1/K7: 登録メソッドは指定の Facade を呼び、tx 本体・他の登録 Facade を直接呼ばない")
    void R1_登録メソッドは指定のFacadeを呼びtx本体を直接呼ばない() {
        assertThat(r1ControllerCallsFacadeNotTxBody(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("R2/AC-15: Facade にクラス・メソッドとも @Transactional が無い")
    void R2_Facadeにtransactionalが無い() {
        assertThat(r2FacadeHasNoTransactional(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("R3/AC-15: Facade の public メソッドはすべて Gate か AccessControlService へ届く（深さ 2）")
    void R3_Facadeのpublicメソッドは認可へ届く() {
        assertThat(r3FacadePublicMethodsReachAuthz(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("R4/AC-15: tx 本体は Gate・AccessControlService に依存しない（CLASS はクラスごと、METHOD は呼ばれるメソッド）")
    void R4_tx本体は認可クラスに依存しない() {
        assertThat(r4TxBodiesDoNotReachAuthz(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("R5/K7: 登録メソッドに @AuthorizedInService・@SelfScopedEndpoint が付いていない")
    void R5_登録メソッドに認可の印が無い() {
        assertThat(r5NoAuthzMarkers(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("R6/AC-8: Facade・tx 本体での throw 形（check*）の直接呼び出しは理由付きの許可リストだけ")
    void R6_throw形の直接呼び出しは許可リストだけ() {
        assertThat(r6ThrowFormOnlyInAllowlist(classes, RULES)).isEmpty();
    }

    @Test
    @DisplayName("R6 のカナリア: 許可リストを空にすると、現行の許可項目がちょうど赤になる（検出が生きている）")
    void R6_許可リストを空にすると赤になる() {
        List<String> violations = r6ThrowFormOnlyInAllowlist(classes, RULES.withThrowFormAllowlist(Map.of()));
        assertThat(violations).hasSize(THROW_FORM_ALLOWLIST.size());
        for (String key : THROW_FORM_ALLOWLIST.keySet()) {
            assertThat(violations).anyMatch(v -> v.startsWith(key + " "));
        }
    }

    @Test
    @DisplayName("R7: Facade の名前は *Facade で、*AccessService / *AccessGuard / *AccessGate でない")
    void R7_Facadeの命名() {
        assertThat(r7FacadeNaming(RULES)).isEmpty();
    }

    @Test
    @DisplayName("許可リスト（R6）と除外表（完全性）の各項目に理由がある")
    void 許可リストと除外表に理由がある() {
        assertThat(blankReasons(RULES)).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 規則（対照テストからも呼ぶ）
    // ═════════════════════════════════════════════════════════════════════

    static List<String> registryTargetsExist(JavaClasses classes, Rules rules) {
        List<String> violations = new ArrayList<>();
        for (Entry e : rules.entries()) {
            if (!classes.contain(e.controller())) {
                violations.add(e.key() + ": Controller が無い");
            } else if (logicalMethods(classes.get(e.controller()), e.method()).isEmpty()) {
                violations.add(e.key() + ": メソッドが無い");
            }
        }
        for (String facade : rules.facades()) {
            if (!classes.contain(facade)) {
                violations.add(facade + ": Facade が無い");
            }
        }
        for (String tx : rules.txBodyClasses()) {
            if (!classes.contain(tx)) {
                violations.add(tx + ": tx 本体が無い");
            }
        }
        for (String key : rules.throwFormAllowlist().keySet()) {
            String[] parts = key.split("#", 2);
            if (!classes.contain(parts[0]) || logicalMethods(classes.get(parts[0]), parts[1]).isEmpty()) {
                violations.add(key + ": R6 の許可リストの項目が実在しない");
            }
        }
        for (String key : rules.unregisteredFacadeCallers().keySet()) {
            String[] parts = key.split("#", 2);
            if (!classes.contain(parts[0]) || logicalMethods(classes.get(parts[0]), parts[1]).isEmpty()) {
                violations.add(key + ": 除外表の項目が実在しない");
            }
        }
        return violations;
    }

    static List<String> completeness(JavaClasses classes, Rules rules) {
        Set<String> facades = rules.facades();
        Set<String> registered = rules.entries().stream().map(Entry::key).collect(Collectors.toSet());
        Set<String> callers = new LinkedHashSet<>();
        for (JavaClass c : classes) {
            if (!c.getSimpleName().endsWith("Controller")) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                if (m.getMethodCallsFromSelf().stream().anyMatch(call -> facades.contains(call.getTargetOwner().getName()))) {
                    callers.add(c.getName() + "#" + logicalName(m));
                }
            }
        }
        List<String> violations = new ArrayList<>();
        for (String caller : callers) {
            if (!registered.contains(caller) && !rules.unregisteredFacadeCallers().containsKey(caller)) {
                violations.add(caller + " は登録 Facade を呼ぶが登録表に無い（登録するか、理由を付けて除外表へ）");
            }
        }
        for (String exempt : rules.unregisteredFacadeCallers().keySet()) {
            if (!callers.contains(exempt)) {
                violations.add(exempt + " は除外表にあるが登録 Facade を呼んでいない（除外表から消す）");
            }
            if (registered.contains(exempt)) {
                violations.add(exempt + " は登録表と除外表の両方にある");
            }
        }
        return violations;
    }

    static List<String> r1ControllerCallsFacadeNotTxBody(JavaClasses classes, Rules rules) {
        Set<String> txBodies = rules.txBodyClasses();
        Set<String> facades = rules.facades();
        List<String> violations = new ArrayList<>();
        for (Entry e : rules.entries()) {
            if (!classes.contain(e.controller())) {
                violations.add(e.key() + ": Controller が無い");
                continue;
            }
            JavaClass controller = classes.get(e.controller());
            List<JavaMethodCall> calls = callsOfLogical(controller, e.method());
            if (calls.stream().noneMatch(c -> c.getTargetOwner().getName().equals(e.facade()))) {
                violations.add(e.key() + " が " + e.facade() + " を呼んでいない");
            }
            String domain = domainRoot(controller.getPackageName());
            for (JavaMethodCall c : calls) {
                JavaClass owner = c.getTargetOwner();
                String name = owner.getName();
                if (txBodies.contains(name)) {
                    violations.add(e.key() + " が認可を飛ばして tx 本体 " + name + "#" + c.getName() + " を直接呼んでいる");
                } else if (isSameDomainService(owner, domain)) {
                    violations.add(e.key() + " が同ドメインの " + name + "#" + c.getName() + " を直接呼んでいる");
                } else if (facades.contains(name) && !name.equals(e.facade())) {
                    violations.add(e.key() + " が指定外の登録 Facade " + name + " を呼んでいる");
                }
            }
        }
        return violations;
    }

    static List<String> r2FacadeHasNoTransactional(JavaClasses classes, Rules rules) {
        List<String> violations = new ArrayList<>();
        for (String name : rules.facades()) {
            if (!classes.contain(name)) {
                violations.add(name + ": Facade が無い");
                continue;
            }
            JavaClass facade = classes.get(name);
            if (isTransactional(facade)) {
                violations.add(name + " のクラスに @Transactional がある");
            }
            for (JavaMethod m : facade.getMethods()) {
                if (isTransactional(m)) {
                    violations.add(m.getFullName() + " に @Transactional がある");
                }
            }
        }
        return violations;
    }

    static List<String> r3FacadePublicMethodsReachAuthz(JavaClasses classes, Rules rules) {
        List<String> violations = new ArrayList<>();
        Set<JavaMethod> checked = new LinkedHashSet<>();
        for (String name : rules.facades()) {
            if (!classes.contain(name)) {
                violations.add(name + ": Facade が無い");
                continue;
            }
            JavaClass facade = classes.get(name);
            // 継承した public メソッドも含める（子がオーバーライド済みの親宣言・Object 由来・抽象・合成は除く）
            List<JavaMethod> publicMethods = new ArrayList<>(effectivePublicMethods(facade));
            // 登録 Controller が実際に呼ぶ Facade のメソッド（解決先の宣言所有型で検査する）
            for (Entry e : rules.entries()) {
                if (!e.facade().equals(name) || !classes.contain(e.controller())) {
                    continue;
                }
                for (JavaMethodCall call : callsOfLogical(classes.get(e.controller()), e.method())) {
                    if (!call.getTargetOwner().getName().equals(name)) {
                        continue;
                    }
                    call.getTarget().resolveMember()
                            .filter(m -> !m.getOwner().isEquivalentTo(Object.class))
                            .filter(m -> !publicMethods.contains(m))
                            .ifPresent(publicMethods::add);
                }
            }
            if (publicMethods.isEmpty()) {
                violations.add(name + " に public メソッドが無い");
            }
            for (JavaMethod m : publicMethods) {
                if (!checked.add(m)) {
                    continue;
                }
                if (!reachesAuthorization(facade, m, 2, rules.authzClasses())) {
                    violations.add(m.getFullName() + " は認可クラスへ届かない（認可が空洞化している）");
                }
            }
        }
        return violations;
    }

    static List<String> r4TxBodiesDoNotReachAuthz(JavaClasses classes, Rules rules) {
        List<String> violations = new ArrayList<>();
        // CLASS: クラスごと認可クラスに依存しない
        Set<String> checkedClass = new LinkedHashSet<>();
        for (List<TxBody> bodies : rules.txBodiesByFacade().values()) {
            for (TxBody body : bodies) {
                if (body.mode() != TxMode.CLASS || !checkedClass.add(body.fqn())) {
                    continue;
                }
                if (!classes.contain(body.fqn())) {
                    violations.add(body.fqn() + ": tx 本体が無い");
                    continue;
                }
                classes.get(body.fqn()).getDirectDependenciesFromSelf().stream()
                        .filter(d -> rules.authzClasses().contains(d.getTargetClass().getName()))
                        .map(d -> body.fqn() + " → " + d.getTargetClass().getName() + "（" + d.getDescription() + "）")
                        .distinct()
                        .forEach(violations::add);
            }
        }
        // 登録メソッドごと: Facade から呼ばれる tx 本体のメソッドを集め、METHOD は認可へ届かないことを見る
        for (Entry e : rules.entries()) {
            if (!classes.contain(e.controller()) || !classes.contain(e.facade())) {
                continue;
            }
            List<TxBody> bodies = rules.txBodiesByFacade().getOrDefault(e.facade(), List.of());
            Map<String, TxMode> modeByClass = new LinkedHashMap<>();
            bodies.forEach(b -> modeByClass.put(b.fqn(), b.mode()));
            JavaClass facade = classes.get(e.facade());
            String facadeDomain = domainRoot(facade.getPackageName());
            Set<JavaMethod> reached = new LinkedHashSet<>();
            for (JavaMethodCall call : callsOfLogical(classes.get(e.controller()), e.method())) {
                if (!call.getTargetOwner().getName().equals(e.facade())) {
                    continue;
                }
                call.getTarget().resolveMember().ifPresent(fm -> collectCallsFromFacade(facade, fm, reached));
            }
            boolean anyTxBody = false;
            for (JavaMethod target : reached) {
                JavaClass owner = target.getOwner();
                TxMode mode = modeByClass.get(owner.getName());
                if (mode == null) {
                    if (isSameDomainService(owner, facadeDomain)) {
                        violations.add(e.key() + ": " + e.facade() + " が tx 本体の表に無い同ドメインの "
                                + target.getFullName() + " を呼んでいる（tx 本体として登録する）");
                    }
                    continue;
                }
                anyTxBody = true;
                if (mode == TxMode.METHOD
                        && reachesAuthorization(owner, target, 3, rules.authzClasses())) {
                    violations.add(e.key() + ": tx 本体 " + target.getFullName() + " が認可クラスへ届く");
                }
            }
            if (!anyTxBody) {
                violations.add(e.key() + ": " + e.facade() + " が tx 本体を呼んでいない（検査が空振りする）");
            }
        }
        return violations;
    }

    static List<String> r5NoAuthzMarkers(JavaClasses classes, Rules rules) {
        List<String> violations = new ArrayList<>();
        for (Entry e : rules.entries()) {
            if (!classes.contain(e.controller())) {
                continue;
            }
            for (JavaMethod m : logicalMethods(classes.get(e.controller()), e.method())) {
                if (m.isAnnotatedWith(AuthorizedInService.class)) {
                    violations.add(m.getFullName() + " に @AuthorizedInService が残っている");
                }
                if (m.isAnnotatedWith(SelfScopedEndpoint.class)) {
                    violations.add(m.getFullName() + " に @SelfScopedEndpoint が付いている");
                }
            }
        }
        return violations;
    }

    static List<String> r6ThrowFormOnlyInAllowlist(JavaClasses classes, Rules rules) {
        Set<String> scanned = new LinkedHashSet<>(rules.facades());
        rules.txBodiesByFacade().values().stream().flatMap(List::stream)
                .filter(b -> b.mode() == TxMode.CLASS).map(TxBody::fqn).forEach(scanned::add);
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (String name : scanned) {
            if (!classes.contain(name)) {
                continue;
            }
            for (JavaMethod m : classes.get(name).getMethods()) {
                for (JavaMethodCall call : m.getMethodCallsFromSelf()) {
                    if (call.getTargetOwner().getName().equals(rules.throwFormOwner()) && isThrowForm(call)) {
                        found.computeIfAbsent(name + "#" + logicalName(m), k -> new ArrayList<>())
                                .add(call.getName() + "（行 " + call.getLineNumber() + "）");
                    }
                }
            }
        }
        List<String> violations = new ArrayList<>();
        found.forEach((key, calls) -> {
            if (!rules.throwFormAllowlist().containsKey(key)) {
                violations.add(key + " が throw 形 " + calls + " を直接呼んでいる（越境を 403 で返すと実在が割れる。"
                        + "Gate か boolean 形へ寄せるか、理由を付けて許可リストへ）");
            }
        });
        for (String key : rules.throwFormAllowlist().keySet()) {
            if (!found.containsKey(key)) {
                violations.add(key + " は許可リストにあるが throw 形を呼んでいない（許可リストから消す）");
            }
        }
        return violations;
    }

    static List<String> r7FacadeNaming(Rules rules) {
        List<String> violations = new ArrayList<>();
        for (String name : rules.facades()) {
            String simple = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
            if (!simple.endsWith("Facade") || simple.endsWith("AccessService") || simple.endsWith("AccessGuard")
                    || simple.endsWith("AccessGate")) {
                violations.add(name + " の名前が *Facade でない（*AccessService/*AccessGuard/*AccessGate は認可シグナル扱いになる）");
            }
        }
        return violations;
    }

    static List<String> blankReasons(Rules rules) {
        List<String> violations = new ArrayList<>();
        rules.throwFormAllowlist().forEach((k, v) -> {
            if (v == null || v.isBlank()) {
                violations.add("R6 の許可リスト " + k + " に理由が無い");
            }
        });
        rules.unregisteredFacadeCallers().forEach((k, v) -> {
            if (v == null || v.isBlank()) {
                violations.add("除外表 " + k + " に理由が無い");
            }
        });
        return violations;
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** ラムダ（{@code lambda$create$0}）は囲むメソッド名（{@code create}）に寄せる。 */
    static String logicalName(JavaMethod m) {
        String name = m.getName();
        if (name.startsWith("lambda$")) {
            String[] parts = name.split("\\$");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return name;
    }

    /** 同名のメソッド（オーバーロード）とそのラムダ。 */
    private static List<JavaMethod> logicalMethods(JavaClass owner, String name) {
        return owner.getMethods().stream().filter(m -> logicalName(m).equals(name)).toList();
    }

    private static List<JavaMethodCall> callsOfLogical(JavaClass owner, String name) {
        List<JavaMethodCall> calls = new ArrayList<>();
        logicalMethods(owner, name).forEach(m -> calls.addAll(m.getMethodCallsFromSelf()));
        return calls;
    }

    /** method 自身と、そのラムダ（{@code lambda$<name>$N}）からの呼び出し。 */
    private static List<JavaMethodCall> callsWithLambdas(JavaClass owner, JavaMethod method) {
        List<JavaMethodCall> calls = new ArrayList<>(method.getMethodCallsFromSelf());
        for (JavaMethod m : owner.getMethods()) {
            if (m.getName().startsWith("lambda$" + method.getName() + "$")) {
                calls.addAll(m.getMethodCallsFromSelf());
            }
        }
        return calls;
    }

    /**
     * Facade が外へ公開する public メソッドのうち、シグネチャ（名前＋引数型）ごとに継承階層で最も子側の
     * 有効な実装だけを返す。子がオーバーライド済みの親クラス・interface の宣言は呼ばれないので除外する。
     * Object 由来・合成は除き、選んだ結果が抽象のものも除く。
     */
    static List<JavaMethod> effectivePublicMethods(JavaClass facade) {
        Map<String, List<JavaMethod>> bySignature = new LinkedHashMap<>();
        for (JavaMethod m : facade.getAllMethods()) {
            if (!m.getModifiers().contains(JavaModifier.PUBLIC)
                    || m.getModifiers().contains(JavaModifier.SYNTHETIC)
                    || m.getOwner().isEquivalentTo(Object.class)) {
                continue;
            }
            String signature = m.getName() + m.getRawParameterTypes().stream()
                    .map(JavaClass::getName).toList();
            bySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(m);
        }
        List<JavaMethod> result = new ArrayList<>();
        for (List<JavaMethod> candidates : bySignature.values()) {
            List<JavaMethod> pool = candidates;
            // クラス上の実装は interface の default より優先する
            if (pool.stream().anyMatch(m -> !m.getOwner().isInterface())) {
                pool = pool.stream().filter(m -> !m.getOwner().isInterface()).toList();
            }
            // より子側の型が同じシグネチャを宣言していれば、その親宣言は隠される
            for (JavaMethod m : pool) {
                boolean hidden = pool.stream().anyMatch(o -> o != m
                        && !o.getOwner().equals(m.getOwner())
                        && o.getOwner().isAssignableTo(m.getOwner().getName()));
                if (!hidden && !m.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    /**
     * method から認可クラスへ届くか。幅優先で探索し、メソッドごとに最初（= 最小深さ）の到達だけを展開するため、
     * 長い経路を先に辿って短い経路を捨てることがない。同クラス内の呼び出し（継承メソッドは宣言所有型を辿る）だけを追う。
     */
    private static boolean reachesAuthorization(JavaClass root, JavaMethod method, int maxDepth,
            Set<String> authzClasses) {
        return bfsReaches(method, maxDepth,
                current -> callsWithLambdas(current.getOwner(), current).stream()
                        .anyMatch(call -> authzClasses.contains(call.getTargetOwner().getName())),
                current -> {
                    List<JavaMethod> callees = new ArrayList<>();
                    for (JavaMethodCall call : callsWithLambdas(current.getOwner(), current)) {
                        Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                        if (resolved.isPresent() && isSameClassCall(root, current, call, resolved.get())) {
                            callees.add(resolved.get());
                        }
                    }
                    return callees;
                });
    }

    /**
     * 探索ロジック本体（グラフ走査のみ）。ノードごとに最初（= 最小深さ）の到達だけを展開する幅優先探索。
     * 呼び先の列挙順（{@code callees} の返す順）に結果が依存しないことを、順序を明示した入力で検証できるよう切り出している。
     *
     * @param directlyAuthz そのノード自身が認可クラスを呼ぶか
     * @param callees       そのノードの呼び先（同クラス内）
     */
    static <N> boolean bfsReaches(N start, int maxDepth, java.util.function.Predicate<N> directlyAuthz,
            java.util.function.Function<N, List<N>> callees) {
        Set<N> visited = new LinkedHashSet<>();
        List<N> frontier = new ArrayList<>(List.of(start));
        visited.add(start);
        for (int depth = 0; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            List<N> next = new ArrayList<>();
            for (N current : frontier) {
                if (directlyAuthz.test(current)) {
                    return true;
                }
                if (depth == maxDepth) {
                    continue;
                }
                for (N callee : callees.apply(current)) {
                    if (visited.add(callee)) {
                        next.add(callee);
                    }
                }
            }
            frontier = next;
        }
        return false;
    }

    private static boolean isSameClassCall(JavaClass root, JavaMethod current, JavaMethodCall call, JavaMethod resolved) {
        return call.getTargetOwner().equals(root) || call.getTargetOwner().equals(current.getOwner())
                || resolved.getOwner().equals(current.getOwner());
    }

    /** Facade のメソッド（と同クラスのメソッド・ラムダを深さ 2 まで、幅優先）から呼ばれる、他クラスのメソッドを集める。 */
    private static void collectCallsFromFacade(JavaClass facade, JavaMethod method, Set<JavaMethod> out) {
        Set<JavaMethod> visited = new LinkedHashSet<>();
        List<JavaMethod> frontier = new ArrayList<>(List.of(method));
        visited.add(method);
        for (int depth = 0; depth <= 2 && !frontier.isEmpty(); depth++) {
            List<JavaMethod> next = new ArrayList<>();
            for (JavaMethod current : frontier) {
                for (JavaMethodCall call : callsWithLambdas(current.getOwner(), current)) {
                    Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                    if (resolved.isEmpty()) {
                        continue;
                    }
                    if (isSameClassCall(facade, current, call, resolved.get())) {
                        if (depth < 2 && visited.add(resolved.get())) {
                            next.add(resolved.get());
                        }
                    } else {
                        out.add(resolved.get());
                    }
                }
            }
            frontier = next;
        }
    }

    /** throw 形 = {@code check*} で戻り値が void のメソッド。 */
    private static boolean isThrowForm(JavaMethodCall call) {
        return call.getName().startsWith("check")
                && call.getTarget().getRawReturnType().getName().equals("void");
    }

    /** パッケージ末尾の controller / service を落としたドメインの根（例: com.mannschaft.app.shift）。 */
    static String domainRoot(String packageName) {
        for (String suffix : List.of(".controller", ".service")) {
            int i = packageName.lastIndexOf(suffix);
            if (i > 0 && (i + suffix.length() == packageName.length() || packageName.charAt(i + suffix.length()) == '.')) {
                return packageName.substring(0, i);
            }
        }
        return packageName;
    }

    private static boolean isSameDomainService(JavaClass owner, String domain) {
        String pkg = owner.getPackageName();
        return (pkg.equals(domain) || pkg.startsWith(domain + "."))
                && owner.getSimpleName().endsWith("Service")
                && !owner.getSimpleName().endsWith("Facade");
    }

    private static boolean isTransactional(JavaClass c) {
        return c.isAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                || c.isMetaAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                || c.isAnnotatedWith(jakarta.transaction.Transactional.class);
    }

    private static boolean isTransactional(JavaMethod m) {
        return m.isAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                || m.isMetaAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                || m.isAnnotatedWith(jakarta.transaction.Transactional.class);
    }
}
