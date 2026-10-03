package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * F03.16 {@link ScheduleCommentAccessGuard} の投稿権限まわりの単体テスト。
 *
 * <h2>本テストが守っているもの — GUEST の投稿禁止（§2.1）</h2>
 * <p>設計書 §2.1 の欄外注記は「本書の GUEST は未ログインの閲覧者を指す」と書いているが、
 * <b>実装上の GUEST は実在する最下位の認証済みロールである</b>:</p>
 * <ul>
 *   <li>{@code AccessControlService#resolveEffectiveRoleName} は「user_roles GUEST のみ →
 *       {@code "GUEST"}」と Javadoc に明記しており、認証済みユーザーに {@code "GUEST"} を返しうる</li>
 *   <li>ロール序列は {@code SYSTEM_ADMIN(1) > ADMIN(2) > DEPUTY_ADMIN(3) > MEMBER(4)
 *       > SUPPORTER(5) > GUEST(6)}</li>
 * </ul>
 * <p>したがって「未認証は Spring Security が 401 で弾くから GUEST は来ない」という前提は<b>誤り</b>で、
 * GUEST ロール保持者は認証を通過して投稿できてしまう。これは設計書 §2.1
 * 「GUEST は投稿・返信・編集・削除は不可」に対する認可の穴であり、本テストがその再発を防ぐ。</p>
 *
 * <p><b>下限が MEMBER ではなく SUPPORTER である根拠</b>: §2.1 の表は SUPPORTER に
 * 「投稿・返信・自分のコメントの編集/削除が可能」を明示的に許可しており、同節の根拠文も
 * 「読める人は書ける（<b>GUEST を除く</b>）」と除外対象を GUEST だけに限っている。
 * 「MEMBER 未満は一律不可」に締めると応援者・保護者を締め出し、設計書の既定に反する。</p>
 */
@DisplayName("F03.16 ScheduleCommentAccessGuard 投稿権限（§2.1）")
class ScheduleCommentAccessGuardTest {

    private static final Long TEAM_ID = 42L;
    private static final Long SCHEDULE_ID = 100L;
    private static final Long USER_ID = 7L;

    private ContentVisibilityChecker checker;
    private AccessControlService accessControlService;
    private ScheduleCommentAccessGuard guard;

    @BeforeEach
    void setUp() {
        checker = Mockito.mock(ContentVisibilityChecker.class);
        accessControlService = Mockito.mock(AccessControlService.class);
        guard = new ScheduleCommentAccessGuard(checker, accessControlService);

        // 親予定は「閲覧できる」状態を既定にする（投稿側の判定だけを見たいため）。
        when(checker.canView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID)).thenReturn(true);
        when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
    }

    /** {@code min_view_role = ANYONE}（誰でも読める）チーム予定。GUEST でも閲覧は通る構成。 */
    private ScheduleEntity openSchedule() {
        return ScheduleEntity.builder()
                .id(SCHEDULE_ID)
                .teamId(TEAM_ID)
                .title("F0316 認可検証")
                .startAt(LocalDateTime.of(2026, 9, 25, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 25, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ANYONE)
                .status(ScheduleStatus.SCHEDULED)
                .commentsEnabled(true)
                .createdBy(999L)
                .build();
    }

    private void givenEffectiveRole(String roleName) {
        when(accessControlService.resolveEffectiveRoleName(USER_ID, TEAM_ID, "TEAM"))
                .thenReturn(roleName);
    }

    @Nested
    @DisplayName("GUEST は投稿できない（認可の穴の回帰テスト）")
    class GuestCannotPost {

        @Test
        @DisplayName("GUEST ロールを持つ認証済ユーザーの投稿は 403 SCHEDULE_COMMENT_004（401 に頼らない）")
        void GUESTロール保持者は403で拒否される() {
            givenEffectiveRole("GUEST");

            assertThatThrownBy(() -> guard.requirePostable(USER_ID, openSchedule()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .as("認証済みだが投稿要件を満たさない場合は 403 SCHEDULE_COMMENT_004（§2.1）")
                            .isEqualTo("SCHEDULE_COMMENT_004"));
        }

        @Test
        @DisplayName("前提の自己検証 — GUEST は閲覧そのものは通っている（閲覧で弾かれた偽の緑ではない）")
        void GUESTは閲覧を通過している() {
            givenEffectiveRole("GUEST");
            // 閲覧だけなら成功する構成であることを確かめる。ここが失敗するなら
            // 上のテストは「投稿権限」ではなく「閲覧権限」で落ちていたことになる。
            assertThatCode(() -> guard.requireScheduleViewable(USER_ID, openSchedule()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("スコープにロールが無い（role == null）ユーザーも 403（AC-15b）")
        void ロール無しも403で拒否される() {
            givenEffectiveRole(null);

            assertThatThrownBy(() -> guard.requirePostable(USER_ID, openSchedule()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("SCHEDULE_COMMENT_004"));
        }

        @Test
        @DisplayName("序列に未登録の並行ロール（JOBBER 等）のみの利用者も fail-closed で 403")
        void 未登録ロールのみも403で拒否される() {
            givenEffectiveRole("JOBBER");

            assertThatThrownBy(() -> guard.requirePostable(USER_ID, openSchedule()))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("SUPPORTER 以上は投稿できる（締めすぎていないことの担保）")
    class SupporterAndAboveCanPost {

        @ParameterizedTest(name = "{0} は投稿できる")
        @ValueSource(strings = {"SUPPORTER", "MEMBER", "DEPUTY_ADMIN", "ADMIN"})
        @DisplayName("SUPPORTER / MEMBER / DEPUTY_ADMIN / ADMIN は投稿できる")
        void SUPPORTER以上は投稿できる(String roleName) {
            givenEffectiveRole(roleName);

            assertThatCode(() -> guard.requirePostable(USER_ID, openSchedule()))
                    .as("§2.1 は SUPPORTER に投稿・返信を明示的に許可している。"
                            + "MEMBER 未満を一律不可に締めると応援者・保護者を締め出す")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は投稿できる")
        void SYSTEM_ADMINは投稿できる() {
            when(accessControlService.isSystemAdmin(USER_ID)).thenReturn(true);

            assertThatCode(() -> guard.requirePostable(USER_ID, openSchedule()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("判定に使う序列は既存の単一正準 RolePriority である（GUEST < SUPPORTER の関係を固定）")
    void 序列判定は既存の単一正準を使う() {
        // 独自の "GUEST".equals(...) 文字列比較を書かないための土台。
        // ロールが増減しても、この序列に従う限り本ガードは追従する。
        assertThat(RolePriority.isAtLeast("GUEST", "SUPPORTER"))
                .as("GUEST(6) は SUPPORTER(5) 未満なので投稿不可側に落ちる")
                .isFalse();
        assertThat(RolePriority.isAtLeast("SUPPORTER", "SUPPORTER")).isTrue();
        assertThat(RolePriority.isAtLeast("MEMBER", "SUPPORTER")).isTrue();
        assertThat(RolePriority.isAtLeast(null, "SUPPORTER")).isFalse();
    }

    @Test
    @DisplayName("閲覧できない予定への投稿は 404 が先に立つ（409/403 より前・存在秘匿）")
    void 閲覧不可なら404が優先する() {
        when(checker.canView(any(), anyLong(), anyLong())).thenReturn(false);
        givenEffectiveRole("MEMBER");

        assertThatThrownBy(() -> guard.requirePostable(USER_ID, openSchedule()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .as("判定順は 閲覧 → writable → ロール。順序が崩れると存在が漏れる")
                        .isEqualTo("SCHEDULE_COMMENT_002"));
    }
}
