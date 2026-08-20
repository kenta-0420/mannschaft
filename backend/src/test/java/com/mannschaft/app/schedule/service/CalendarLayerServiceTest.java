package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CalendarColorSource;
import com.mannschaft.app.schedule.dto.CalendarLayerResponse;
import com.mannschaft.app.schedule.dto.CalendarLayerUpdateRequest;
import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03.19 W1-b — {@link CalendarLayerService} の単体テスト。
 *
 * <p>設計書 {@code docs/features/F03.19_unified_calendar_view.md} §4.3〜4.5・§7・§10.1。
 * 受け入れ条件 AC-06/07（自動色）・AC-08b（部分更新）・AC-09（色バリデーション）・
 * AC-10（非所属 403・行を作らない）・AC-10b（DELETE 冪等）・AC-10b2（membership 由来所属）・
 * AC-10d（行数上限 1000）に対応する。</p>
 *
 * <p><b>認可の陰性対照を必ず含む</b>: 「所属者が設定できる」だけでなく、
 * 「非所属者が設定できない」「他人の設定を読み書きできない」を明示的に検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CalendarLayerService（F03.19 レイヤー設定）")
class CalendarLayerServiceTest {

    private static final Long ME = 1001L;
    private static final Long OTHER_USER = 2002L;

    /** 所属チーム（user_roles 由来）。 */
    private static final Long MY_TEAM = 42L;
    /** 所属チーム（memberships 専属＝user_roles に行が無い）。 */
    private static final Long MEMBERSHIP_ONLY_TEAM = 43L;
    /** 非所属チーム。 */
    private static final Long FOREIGN_TEAM = 900L;
    /** 実在しないチーム。 */
    private static final Long GHOST_TEAM = 999999L;
    /** 所属組織。 */
    private static final Long MY_ORG = 7L;

    @Mock
    private UserCalendarLayerSettingRepository repository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private CalendarLayerService service;

    @BeforeEach
    void setUp() {
        // 所属集合は AccessControlService の共通窓口（user_roles ∪ memberships）から得る。
        // MEMBERSHIP_ONLY_TEAM は membership 専属の所属を模す（R3・AC-10b2）。
        lenient().when(accessControlService.findAffiliatedScopeIds(ME, "TEAM"))
                .thenReturn(new LinkedHashSet<>(List.of(MY_TEAM, MEMBERSHIP_ONLY_TEAM)));
        lenient().when(accessControlService.findAffiliatedScopeIds(ME, "ORGANIZATION"))
                .thenReturn(new LinkedHashSet<>(List.of(MY_ORG)));
        lenient().when(accessControlService.findAffiliatedScopeIds(OTHER_USER, "TEAM"))
                .thenReturn(new LinkedHashSet<>(List.of(FOREIGN_TEAM)));
        lenient().when(accessControlService.findAffiliatedScopeIds(OTHER_USER, "ORGANIZATION"))
                .thenReturn(new LinkedHashSet<>());

        lenient().when(nameResolverService.resolveTeamNames(any()))
                .thenReturn(Map.of(MY_TEAM, "青葉FC", MEMBERSHIP_ONLY_TEAM, "白鳥SC"));
        lenient().when(nameResolverService.resolveOrganizationNames(any()))
                .thenReturn(Map.of(MY_ORG, "青葉スポーツクラブ"));
        lenient().when(nameResolverService.resolveTeamIconUrls(any()))
                .thenReturn(Map.of(MY_TEAM, "https://example.test/signed/team42.png"));
        lenient().when(nameResolverService.resolveOrganizationIconUrls(any()))
                .thenReturn(Map.of());

        lenient().when(repository.findByUserId(any())).thenReturn(List.of());
        lenient().when(repository.findByUserIdAndScopeTypeAndScopeId(any(), anyString(), any()))
                .thenReturn(Optional.empty());
        lenient().when(repository.countByUserId(any())).thenReturn(0L);
        // 新規行は INSERT IGNORE で原子的に作る（1 = 自分が作れた）。
        // 並行 PATCH に負けた 0 のケースは CalendarLayerUpsertConcurrencyTest が受け持つ。
        lenient().when(repository.insertIfAbsent(any(), any(), anyString(), any())).thenReturn(1);
        lenient().when(repository.save(any(UserCalendarLayerSettingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static UserCalendarLayerSettingEntity setting(
            Long userId, String scopeType, Long scopeId, String color, boolean hidden) {
        return UserCalendarLayerSettingEntity.builder()
                .userId(userId).scopeType(scopeType).scopeId(scopeId)
                .color(color).hidden(hidden)
                .build();
    }

    private static CalendarLayerResponse layerOf(List<CalendarLayerResponse> layers,
                                                 String scopeType, Long scopeId) {
        return layers.stream()
                .filter(l -> l.scopeType().equals(scopeType) && l.scopeId().equals(scopeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "レイヤーが見つからない: " + scopeType + ":" + scopeId + " / actual=" + layers));
    }

    // ------------------------------------------------------------------
    // GET /me/calendar-layers
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("listLayers（GET /me/calendar-layers）")
    class ListLayers {

        @Test
        @DisplayName("PERSONAL→ORGANIZATION（ID昇順）→TEAM（ID昇順）の安定順で返る（AC-04）")
        void 並び順が安定している() {
            List<CalendarLayerResponse> layers = service.listLayers(ME);

            assertThat(layers).extracting(CalendarLayerResponse::scopeType)
                    .containsExactly("PERSONAL", "ORGANIZATION", "TEAM", "TEAM");
            assertThat(layers).extracting(CalendarLayerResponse::scopeId)
                    .containsExactly(0L, MY_ORG, MY_TEAM, MEMBERSHIP_ONLY_TEAM);
        }

        @Test
        @DisplayName("PERSONAL は scopeId=0・i18nキー付きで必ず先頭に含まれる（R7）")
        void PERSONALレイヤーが常に含まれる() {
            CalendarLayerResponse personal = layerOf(service.listLayers(ME), "PERSONAL", 0L);

            assertThat(personal.scopeId()).isZero();
            assertThat(personal.scopeNameKey()).isEqualTo("schedule.calendar.layer.personal");
            assertThat(personal.scopeName()).isNotNull();
            assertThat(personal.scopeIconUrl()).isNull();
        }

        @Test
        @DisplayName("AC-06: 設定が無いレイヤーは自動色・LAYER_AUTO で返り、チーム間で色が異なる")
        void AC06_設定が無いレイヤーは自動色になる() {
            List<CalendarLayerResponse> layers = service.listLayers(ME);

            CalendarLayerResponse team = layerOf(layers, "TEAM", MY_TEAM);
            CalendarLayerResponse other = layerOf(layers, "TEAM", MEMBERSHIP_ONLY_TEAM);

            assertThat(team.colorSource()).isEqualTo(CalendarColorSource.LAYER_AUTO);
            assertThat(team.color()).isEqualTo(CalendarLayerAutoColor.resolve("TEAM", MY_TEAM));
            assertThat(team.color()).isNotEqualTo(other.color());
            assertThat(team.hidden()).isFalse();
        }

        @Test
        @DisplayName("AC-07: 同じスコープの自動色は呼び出しユーザーが違っても同じ")
        void AC07_自動色は呼び出しユーザーに依存しない() {
            when(accessControlService.findAffiliatedScopeIds(OTHER_USER, "TEAM"))
                    .thenReturn(new LinkedHashSet<>(List.of(MY_TEAM)));

            String mine = layerOf(service.listLayers(ME), "TEAM", MY_TEAM).color();
            String theirs = layerOf(service.listLayers(OTHER_USER), "TEAM", MY_TEAM).color();

            assertThat(mine).isEqualTo(theirs);
        }

        @Test
        @DisplayName("設定行がある場合はユーザー色・LAYER_USER・hidden が反映される")
        void 設定行がある場合はユーザー色が返る() {
            when(repository.findByUserId(ME)).thenReturn(List.of(
                    setting(ME, "TEAM", MY_TEAM, "#DC2626", true)));

            CalendarLayerResponse team = layerOf(service.listLayers(ME), "TEAM", MY_TEAM);

            assertThat(team.color()).isEqualTo("#DC2626");
            assertThat(team.colorSource()).isEqualTo(CalendarColorSource.LAYER_USER);
            assertThat(team.hidden()).isTrue();
        }

        @Test
        @DisplayName("色が NULL の設定行（hidden のみ設定）は自動色にフォールバックする")
        void 色NULLの設定行は自動色になる() {
            when(repository.findByUserId(ME)).thenReturn(List.of(
                    setting(ME, "TEAM", MY_TEAM, null, true)));

            CalendarLayerResponse team = layerOf(service.listLayers(ME), "TEAM", MY_TEAM);

            assertThat(team.color()).isEqualTo(CalendarLayerAutoColor.resolve("TEAM", MY_TEAM));
            assertThat(team.colorSource()).isEqualTo(CalendarColorSource.LAYER_AUTO);
            assertThat(team.hidden()).isTrue();
        }

        @Test
        @DisplayName("【陰性対照】他人の設定行は読まれない（設定の取得は認証主体の user_id 限定）")
        void 陰性対照_他人の設定は読まれない() {
            // 他人（OTHER_USER）が自チームに赤を設定していても、ME の一覧には一切影響しない。
            when(repository.findByUserId(OTHER_USER)).thenReturn(List.of(
                    setting(OTHER_USER, "TEAM", MY_TEAM, "#DC2626", true)));

            CalendarLayerResponse team = layerOf(service.listLayers(ME), "TEAM", MY_TEAM);

            assertThat(team.color()).isEqualTo(CalendarLayerAutoColor.resolve("TEAM", MY_TEAM));
            assertThat(team.hidden()).isFalse();
            verify(repository).findByUserId(ME);
            verify(repository, never()).findByUserId(OTHER_USER);
        }

        @Test
        @DisplayName("【陰性対照】非所属スコープは一覧に現れない")
        void 陰性対照_非所属スコープは一覧に出ない() {
            List<CalendarLayerResponse> layers = service.listLayers(ME);

            assertThat(layers).noneMatch(l -> FOREIGN_TEAM.equals(l.scopeId()));
        }

        @Test
        @DisplayName("AC-10b2: membership 専属で所属するチームも一覧に含まれる（R3）")
        void AC10b2_membership専属のチームも一覧に出る() {
            List<CalendarLayerResponse> layers = service.listLayers(ME);

            assertThat(layers).anyMatch(l -> "TEAM".equals(l.scopeType())
                    && MEMBERSHIP_ONLY_TEAM.equals(l.scopeId()));
            // 所属列挙は AccessControlService の共通窓口を使う（独自クエリを書き起こさない）。
            verify(accessControlService).findAffiliatedScopeIds(ME, "TEAM");
            verify(accessControlService).findAffiliatedScopeIds(ME, "ORGANIZATION");
        }

        @Test
        @DisplayName("名前が解決できないスコープ（削除済み等）は一覧から除外される")
        void 名前が解決できないスコープは除外される() {
            when(nameResolverService.resolveTeamNames(any())).thenReturn(Map.of(MY_TEAM, "青葉FC"));

            List<CalendarLayerResponse> layers = service.listLayers(ME);

            assertThat(layers).noneMatch(l -> MEMBERSHIP_ONLY_TEAM.equals(l.scopeId())
                    && "TEAM".equals(l.scopeType()));
        }
    }

    // ------------------------------------------------------------------
    // PATCH /me/calendar-layers/{scopeType}/{scopeId}
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("updateLayer（PATCH /me/calendar-layers）")
    class UpdateLayer {

        @Test
        @DisplayName("所属チームへの色設定は大文字正規化されて保存される（AC-08 の保存側）")
        void 色は大文字に正規化して保存される() {
            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#dc2626", null));

            ArgumentCaptor<UserCalendarLayerSettingEntity> captor =
                    ArgumentCaptor.forClass(UserCalendarLayerSettingEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(ME);
            assertThat(captor.getValue().getColor()).isEqualTo("#DC2626");
            assertThat(res.color()).isEqualTo("#DC2626");
            assertThat(res.colorSource()).isEqualTo(CalendarColorSource.LAYER_USER);
        }

        @Test
        @DisplayName("設定行が無い状態の PATCH は行を作成し、未指定項目は既定値で埋める")
        void 設定行が無ければ作成する() {
            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null));

            ArgumentCaptor<UserCalendarLayerSettingEntity> captor =
                    ArgumentCaptor.forClass(UserCalendarLayerSettingEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getHidden()).isFalse();
            assertThat(res.hidden()).isFalse();
        }

        @Test
        @DisplayName("AC-08b: color のみ送ると hidden は現在値のまま維持される（部分更新・R2）")
        void AC08b_色だけ送ると隠し状態が保たれる() {
            when(repository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM))
                    .thenReturn(Optional.of(setting(ME, "TEAM", MY_TEAM, null, true)));

            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null));

            assertThat(res.hidden()).isTrue();
            assertThat(res.color()).isEqualTo("#DC2626");
        }

        @Test
        @DisplayName("AC-08b: hidden のみ送ると色設定と colorSource が保持される（部分更新・R2）")
        void AC08b_隠し状態だけ送ると色が保たれる() {
            when(repository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM))
                    .thenReturn(Optional.of(setting(ME, "TEAM", MY_TEAM, "#DC2626", true)));

            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest(null, false));

            assertThat(res.color()).isEqualTo("#DC2626");
            assertThat(res.colorSource()).isEqualTo(CalendarColorSource.LAYER_USER);
            assertThat(res.hidden()).isFalse();
        }

        @Test
        @DisplayName("両方 null（空ボディ相当）は冪等で、現在値をそのまま返す")
        void 空のリクエストは何も変えない() {
            when(repository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM))
                    .thenReturn(Optional.of(setting(ME, "TEAM", MY_TEAM, "#DC2626", true)));

            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest(null, null));

            assertThat(res.color()).isEqualTo("#DC2626");
            assertThat(res.hidden()).isTrue();
        }

        @Test
        @DisplayName("PERSONAL/0 への設定は所属検証なしで成功する（自分自身のレイヤー）")
        void PERSONALレイヤーは設定できる() {
            CalendarLayerResponse res = service.updateLayer(
                    ME, "PERSONAL", 0L, new CalendarLayerUpdateRequest("#DC2626", null));

            assertThat(res.scopeType()).isEqualTo("PERSONAL");
            assertThat(res.scopeId()).isZero();
            assertThat(res.color()).isEqualTo("#DC2626");
        }

        @Test
        @DisplayName("AC-10b2: membership 専属で所属するチームへの PATCH が成功する（R3）")
        void AC10b2_membership専属チームへのPATCHが成功する() {
            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MEMBERSHIP_ONLY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null));

            assertThat(res.scopeId()).isEqualTo(MEMBERSHIP_ONLY_TEAM);
            verify(repository).save(any(UserCalendarLayerSettingEntity.class));
        }

        @Test
        @DisplayName("所属組織への PATCH が成功する")
        void 所属組織へのPATCHが成功する() {
            CalendarLayerResponse res = service.updateLayer(
                    ME, "ORGANIZATION", MY_ORG, new CalendarLayerUpdateRequest(null, true));

            assertThat(res.scopeType()).isEqualTo("ORGANIZATION");
            assertThat(res.hidden()).isTrue();
        }

        // --- 認可（陰性対照） ---

        @Test
        @DisplayName("【陰性対照・AC-10】非所属チームへの PATCH は 403 SCHEDULE_101 で行も作られない")
        void 陰性対照_AC10_非所属チームへのPATCHは403() {
            assertThatThrownBy(() -> service.updateLayer(
                    ME, "TEAM", FOREIGN_TEAM, new CalendarLayerUpdateRequest("#DC2626", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_NOT_MEMBER);

            verify(repository, never()).save(any(UserCalendarLayerSettingEntity.class));
        }

        @Test
        @DisplayName("【陰性対照・AC-10】存在しないチームIDでも同じ 403 SCHEDULE_101（存在秘匿）")
        void 陰性対照_AC10_存在しないチームでも同じ403() {
            assertThatThrownBy(() -> service.updateLayer(
                    ME, "TEAM", GHOST_TEAM, new CalendarLayerUpdateRequest("#DC2626", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_NOT_MEMBER);

            verify(repository, never()).save(any(UserCalendarLayerSettingEntity.class));
        }

        @Test
        @DisplayName("【陰性対照】非所属組織への PATCH も 403 SCHEDULE_101")
        void 陰性対照_非所属組織へのPATCHは403() {
            assertThatThrownBy(() -> service.updateLayer(
                    ME, "ORGANIZATION", 8888L, new CalendarLayerUpdateRequest("#DC2626", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_NOT_MEMBER);
        }

        @Test
        @DisplayName("【陰性対照】保存される user_id は常に呼び出し本人（他人の行を書き換えない）")
        void 陰性対照_保存されるuserIdは常に本人() {
            service.updateLayer(ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null));

            verify(repository).findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM);
            verify(repository, never())
                    .findByUserIdAndScopeTypeAndScopeId(eq(OTHER_USER), anyString(), any());

            ArgumentCaptor<UserCalendarLayerSettingEntity> captor =
                    ArgumentCaptor.forClass(UserCalendarLayerSettingEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(ME);
        }

        // --- バリデーション ---

        @Test
        @DisplayName("AC-09: 不正な色は 422 SCHEDULE_102")
        void AC09_不正な色は422() {
            for (String bad : List.of("red", "#GGGGGG", "#FFF", "#DC26266", "DC2626", "")) {
                assertThatThrownBy(() -> service.updateLayer(
                        ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest(bad, null)))
                        .as("不正な色は 422 SCHEDULE_102 になるべき: %s", bad)
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_INVALID_COLOR);
            }
            verify(repository, never()).save(any(UserCalendarLayerSettingEntity.class));
        }

        @Test
        @DisplayName("不正な scopeType は 422 SCHEDULE_103")
        void 不正なスコープ種別は422() {
            assertThatThrownBy(() -> service.updateLayer(
                    ME, "TEAMS", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
        }

        @Test
        @DisplayName("PERSONAL に 0 以外の scopeId を指定すると 422 SCHEDULE_103（R7）")
        void PERSONALのscopeIdは0以外なら422() {
            assertThatThrownBy(() -> service.updateLayer(
                    ME, "PERSONAL", 5L, new CalendarLayerUpdateRequest("#DC2626", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
        }

        @Test
        @DisplayName("TEAM/ORGANIZATION の scopeId が正でなければ 422 SCHEDULE_103")
        void 正でないスコープIDは422() {
            for (Long bad : List.of(0L, -1L)) {
                assertThatThrownBy(() -> service.updateLayer(
                        ME, "TEAM", bad, new CalendarLayerUpdateRequest("#DC2626", null)))
                        .as("scopeId=%s", bad)
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
            }
        }

        // --- 行数上限（§10.1 / R17） ---

        @Test
        @DisplayName("AC-10d: 設定行が 1000 件ある状態の新規レイヤー PATCH は 400 SCHEDULE_104（件数上限は既定の 400）")
        void AC10d_上限到達時の新規作成は400() {
            when(repository.countByUserId(ME)).thenReturn(1000L);

            assertThatThrownBy(() -> service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_LIMIT_EXCEEDED);

            verify(repository, never()).save(any(UserCalendarLayerSettingEntity.class));
        }

        @Test
        @DisplayName("AC-10d: 上限に達していても既存行の更新は成功する")
        void AC10d_上限到達でも既存行の更新は成功する() {
            when(repository.countByUserId(ME)).thenReturn(1000L);
            when(repository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM))
                    .thenReturn(Optional.of(setting(ME, "TEAM", MY_TEAM, "#059669", false)));

            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null));

            assertThat(res.color()).isEqualTo("#DC2626");
            verify(repository).save(any(UserCalendarLayerSettingEntity.class));
        }

        @Test
        @DisplayName("999 件なら新規作成できる（上限は 1000 件未満）")
        void 上限直前は新規作成できる() {
            when(repository.countByUserId(ME)).thenReturn(999L);

            CalendarLayerResponse res = service.updateLayer(
                    ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null));

            assertThat(res.color()).isEqualTo("#DC2626");
        }
    }

    // ------------------------------------------------------------------
    // DELETE /me/calendar-layers/{scopeType}/{scopeId}
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("deleteLayer（DELETE /me/calendar-layers）")
    class DeleteLayer {

        @Test
        @DisplayName("設定行を物理削除する（本人の user_id に限定）")
        void 設定行を削除する() {
            service.deleteLayer(ME, "TEAM", MY_TEAM);

            verify(repository).deleteByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM);
        }

        @Test
        @DisplayName("AC-10b: 設定行が無くても例外を投げない（冪等・204）")
        void AC10b_設定行が無くても冪等() {
            when(repository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM))
                    .thenReturn(Optional.empty());

            service.deleteLayer(ME, "TEAM", MY_TEAM);

            verify(repository).deleteByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM);
        }

        @Test
        @DisplayName("【陰性対照】非所属チームの DELETE は 403 SCHEDULE_101 で削除も走らない")
        void 陰性対照_非所属チームのDELETEは403() {
            assertThatThrownBy(() -> service.deleteLayer(ME, "TEAM", FOREIGN_TEAM))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_NOT_MEMBER);

            verify(repository, never())
                    .deleteByUserIdAndScopeTypeAndScopeId(any(), anyString(), any());
        }

        @Test
        @DisplayName("不正な scopeType の DELETE は 422 SCHEDULE_103")
        void 不正なスコープ種別のDELETEは422() {
            assertThatThrownBy(() -> service.deleteLayer(ME, "PERSON", 0L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.CALENDAR_LAYER_INVALID_SCOPE);
        }

        @Test
        @DisplayName("PERSONAL/0 の DELETE は所属検証なしで成功する")
        void PERSONALのDELETEは成功する() {
            service.deleteLayer(ME, "PERSONAL", 0L);

            verify(repository).deleteByUserIdAndScopeTypeAndScopeId(ME, "PERSONAL", 0L);
        }
    }

    @Test
    @DisplayName("所属判定は AccessControlService の共通窓口のみに委譲する（独自の所属判定を持たない）")
    void 所属判定は共通窓口に委譲される() {
        Set<Long> teams = new LinkedHashSet<>(List.of(MY_TEAM));
        when(accessControlService.findAffiliatedScopeIds(ME, "TEAM")).thenReturn(teams);

        service.updateLayer(ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", null));

        verify(accessControlService).findAffiliatedScopeIds(ME, "TEAM");
    }
}
