package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timetable.personal.dto.FamilyWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableShareTargetRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.personal.service.FamilyPersonalTimetableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 5 家族からの個人時間割閲覧サービスのユニットテスト。
 *
 * <p>条件不一致は <strong>すべて 404 統一</strong> ({@code PERSONAL_TIMETABLE_NOT_FOUND}) で
 * 返ることを網羅検証する（情報漏洩防止）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FamilyPersonalTimetableService ユニットテスト（404 統一）")
class FamilyPersonalTimetableServiceTest {

    private static final Long TEAM_ID = 50L;
    private static final Long TARGET_USER_ID = 200L;
    private static final Long CURRENT_USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;

    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private PersonalTimetableSlotRepository slotRepository;
    @Mock private PersonalTimetablePeriodRepository periodRepository;
    @Mock private PersonalTimetableShareTargetRepository shareTargetRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks private FamilyPersonalTimetableService service;

    private TeamEntity familyTeam;
    private TeamEntity nonFamilyTeam;
    private PersonalTimetableEntity sharedActiveTimetable;

    @BeforeEach
    void setUp() {
        familyTeam = TeamEntity.builder()
                .name("我が家")
                .template("family")
                .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE)
                .supporterEnabled(false)
                .build();

        nonFamilyTeam = TeamEntity.builder()
                .name("塾")
                .template("school")
                .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE)
                .supporterEnabled(false)
                .build();

        sharedActiveTimetable = PersonalTimetableEntity.builder()
                .userId(TARGET_USER_ID)
                .name("家族共有テスト")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(PersonalTimetableStatus.ACTIVE)
                .visibility(PersonalTimetableVisibility.FAMILY_SHARED)
                .weekPatternEnabled(false)
                .build();
    }

    @Nested
    @DisplayName("404 統一: アクセス検証")
    class AccessValidationTest {

        @Test
        @DisplayName("teamId のチームが存在しない → 404")
        void チーム不存在() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }

        @Test
        @DisplayName("teamId が family テンプレでない → 404")
        void 非家族チーム() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(nonFamilyTeam));

            assertThatThrownBy(() -> service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }

        @Test
        @DisplayName("currentUser がチーム MEMBER でない → 404")
        void currentUser非メンバー() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(CURRENT_USER_ID, TEAM_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }

        /**
         * CMP-050 AC-19: 家族チームに在籍していない userId は状態にかかわらず 404。
         *
         * <p>被参照者の判定を「状態を問わない」版へ載せ替えても、在籍判定そのものは
         * 緩めていないことを締める。</p>
         */
        @Test
        @DisplayName("targetUser が同じチーム MEMBER でない → 404（CMP-050 AC-19）")
        void targetUser非メンバー() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(CURRENT_USER_ID, TEAM_ID))
                    .willReturn(true);
            given(userRoleRepository.existsAnyStatusByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }

        /**
         * CMP-050 AC-17: 保護者（ACTIVE・家族チーム在籍）が FROZEN の子の家族週次ビューを
         * 取得する経路が正常応答すること（例外の証明）。
         *
         * <p>子アカウントは PENDING_PARENTAL_CONSENT / FROZEN を取り得るが、その間も
         * 保護者の家族時間割閲覧は維持する仕様である。したがって被参照者の在籍判定は
         * ACTIVE を問わない版でなければならない。ACTIVE 必須版へ戻す退行を検出するため、
         * {@code existsByUserIdAndTeamId(TARGET_USER_ID, ...)} が一度も呼ばれないことを
         * 明示的に締める（呼ばれた瞬間、凍結中の子の時間割が保護者から見えなくなる）。</p>
         */
        @Test
        @DisplayName("CMP-050 AC-17: 子が非ACTIVEでも保護者の家族閲覧は通る（被参照者はACTIVEを問わない）")
        void cmp050_ac17_子が非ACTIVEでも保護者の家族閲覧は通る() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(CURRENT_USER_ID, TEAM_ID))
                    .willReturn(true);
            // 凍結中の子でも在籍とみなす（状態を問わない判定）
            given(userRoleRepository.existsAnyStatusByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID))
                    .willReturn(true);
            given(shareTargetRepository.findPersonalTimetableIdsByTeamId(TEAM_ID))
                    .willReturn(List.of());

            assertThat(service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID))
                    .as("共有設定が無いだけであり 404 にはならないこと")
                    .isEmpty();

            verify(userRoleRepository, never()).existsByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID);
        }

        /**
         * CMP-050 AC-18: 保護者自身が非 ACTIVE なら 404。
         *
         * <p>閲覧者（＝認可の対象者）の判定は ACTIVE 必須のままであることの証明。
         * 例外化は被参照者側にのみ適用し、権限を与える方向の判定には広げない。</p>
         */
        @Test
        @DisplayName("CMP-050 AC-18: 保護者自身が非ACTIVEなら404（閲覧者はACTIVE必須のまま）")
        void cmp050_ac18_保護者自身が非ACTIVEなら404() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(familyTeam));
            // 凍結された保護者は ACTIVE 必須の在籍判定で false になる
            given(userRoleRepository.existsByUserIdAndTeamId(CURRENT_USER_ID, TEAM_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("listForFamily")
    class ListTest {

        @Test
        @DisplayName("正常系：共有設定済みかつ visibility=FAMILY_SHARED の ACTIVE のみ返す")
        void 正常系() {
            stubAccessOk();
            given(shareTargetRepository.findPersonalTimetableIdsByTeamId(TEAM_ID))
                    .willReturn(List.of(TIMETABLE_ID));
            given(personalTimetableRepository.findActiveByUserId(TARGET_USER_ID))
                    .willReturn(List.of(sharedActiveTimetable));

            // ID をリフレクションで埋める
            setEntityId(sharedActiveTimetable, TIMETABLE_ID);

            var result = service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("共有設定が無い → 空リスト（404 ではない）")
        void 共有設定なし_空リスト() {
            stubAccessOk();
            given(shareTargetRepository.findPersonalTimetableIdsByTeamId(TEAM_ID))
                    .willReturn(List.of());

            var result = service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("visibility=PRIVATE のものはフィルタで除外される")
        void PRIVATEは除外() {
            stubAccessOk();
            PersonalTimetableEntity priv = PersonalTimetableEntity.builder()
                    .userId(TARGET_USER_ID)
                    .name("PRIVATE")
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .status(PersonalTimetableStatus.ACTIVE)
                    .visibility(PersonalTimetableVisibility.PRIVATE)
                    .weekPatternEnabled(false)
                    .build();
            setEntityId(priv, TIMETABLE_ID);

            given(shareTargetRepository.findPersonalTimetableIdsByTeamId(TEAM_ID))
                    .willReturn(List.of(TIMETABLE_ID));
            given(personalTimetableRepository.findActiveByUserId(TARGET_USER_ID))
                    .willReturn(List.of(priv));

            var result = service.listForFamily(TEAM_ID, TARGET_USER_ID, CURRENT_USER_ID);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getWeeklyViewForFamily")
    class WeeklyViewTest {

        @Test
        @DisplayName("正常系：DTO に notes / linked_* / user_note_id 等が含まれない")
        void DTO_除外フィールド検証() {
            stubAccessOk();
            setEntityId(sharedActiveTimetable, TIMETABLE_ID);
            given(personalTimetableRepository.findActiveByIdAndUserId(TIMETABLE_ID, TARGET_USER_ID))
                    .willReturn(Optional.of(sharedActiveTimetable));
            given(shareTargetRepository.findByPersonalTimetableIdAndTeamId(TIMETABLE_ID, TEAM_ID))
                    .willReturn(Optional.of(PersonalTimetableShareTargetEntity.builder()
                            .personalTimetableId(TIMETABLE_ID)
                            .teamId(TEAM_ID)
                            .build()));
            given(slotRepository.findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(
                    TIMETABLE_ID))
                    .willReturn(List.of());
            given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                    .willReturn(List.of());

            FamilyWeeklyViewResponse resp = service.getWeeklyViewForFamily(
                    TEAM_ID, TARGET_USER_ID, TIMETABLE_ID, CURRENT_USER_ID,
                    LocalDate.of(2026, 5, 4));

            assertThat(resp.personalTimetableId()).isEqualTo(TIMETABLE_ID);
            assertThat(resp.days()).hasSize(7);
            // FamilySlotInfo の record 定義に linked_* / notes / user_note_id が含まれないことは
            // record 構造そのもので保証されている（コンパイル時保証）
        }

        @Test
        @DisplayName("対象個人時間割が ACTIVE でない / 共有設定なし → 404")
        void 対象なし_404() {
            stubAccessOk();
            given(personalTimetableRepository.findActiveByIdAndUserId(TIMETABLE_ID, TARGET_USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getWeeklyViewForFamily(
                    TEAM_ID, TARGET_USER_ID, TIMETABLE_ID, CURRENT_USER_ID,
                    LocalDate.of(2026, 5, 4)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }

        @Test
        @DisplayName("share_targets に当該家族チームが含まれない → 404")
        void share_targets未登録_404() {
            stubAccessOk();
            setEntityId(sharedActiveTimetable, TIMETABLE_ID);
            given(personalTimetableRepository.findActiveByIdAndUserId(TIMETABLE_ID, TARGET_USER_ID))
                    .willReturn(Optional.of(sharedActiveTimetable));
            given(shareTargetRepository.findByPersonalTimetableIdAndTeamId(TIMETABLE_ID, TEAM_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getWeeklyViewForFamily(
                    TEAM_ID, TARGET_USER_ID, TIMETABLE_ID, CURRENT_USER_ID,
                    LocalDate.of(2026, 5, 4)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }
    }

    /**
     * CMP-050: 閲覧者（currentUser）は ACTIVE 必須の在籍判定、
     * 被参照者（targetUser = 家族の子）は状態を問わない在籍判定を用いる。
     */
    private void stubAccessOk() {
        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(familyTeam));
        given(userRoleRepository.existsByUserIdAndTeamId(CURRENT_USER_ID, TEAM_ID))
                .willReturn(true);
        given(userRoleRepository.existsAnyStatusByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID))
                .willReturn(true);
    }

    /** 動的に id を設定するヘルパ（BaseEntity の id がリフレクション必須）。 */
    private static void setEntityId(PersonalTimetableEntity entity, Long id) {
        try {
            // BaseEntity の id フィールドは通常 superclass にある
            Class<?> clazz = entity.getClass();
            while (clazz != null) {
                try {
                    java.lang.reflect.Field f = clazz.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException ignored) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new IllegalStateException("id field not found");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
