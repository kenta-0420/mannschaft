package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.family.service.CareLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * {@link GuardianshipSwitchService} 集約テスト（F08.9 P3a 切替可能な子の列挙）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>承認済み保護者リンクの子のみ対象（無関係ユーザーは含まない）</li>
 *   <li>年齢ポリシーで {@code switchAllowed} により children / blockedChildren を分離</li>
 *   <li>parental_consent と care_links の 2 経路を和集合化（重複排除）</li>
 *   <li>birthDate 欠落の子は安全側で封印</li>
 * </ul>
 *
 * <p>Clock は {@code Asia/Tokyo} 固定で date-pin（CI を固定日付で塞がない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipSwitchService 集約テスト（F08.9 P3a 切替可能な子の列挙）")
class SwitchableChildrenServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Long GUARDIAN_ID = 100L;
    /** 基準日 2026-04-01（JST）。この日、2013-04-02 生まれは封印、2014-04-02 生まれは切替可。 */
    private static final LocalDate BASE_DATE = LocalDate.parse("2026-04-01");

    @Mock
    private ParentalConsentService parentalConsentService;
    @Mock
    private CareLinkService careLinkService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private com.mannschaft.app.auth.service.AuditLogService auditLogService;
    @Mock
    private com.mannschaft.app.proxy.repository.ProxyInputRecordRepository proxyInputRecordRepository;

    private GuardianshipSwitchService service;

    @BeforeEach
    void setUp() {
        Clock fixedJstClock = Clock.fixed(
                BASE_DATE.atTime(LocalTime.NOON).atZone(JST).toInstant(), JST);
        // 実物のレジストリ（JP + フォールバック）で結線する。
        JapanGuardianshipAgePolicy japanPolicy = new JapanGuardianshipAgePolicy();
        DefaultGuardianshipAgePolicy defaultPolicy = new DefaultGuardianshipAgePolicy();
        GuardianshipAgePolicyRegistry registry =
                new GuardianshipAgePolicyRegistry(List.of(japanPolicy, defaultPolicy), defaultPolicy);

        service = new GuardianshipSwitchService(
                parentalConsentService, careLinkService, userRepository, registry,
                auditLogService, proxyInputRecordRepository, fixedJstClock);
    }

    private UserEntity child(Long id, String displayName, String birthDate, String countryCode) {
        UserEntity user = UserEntity.builder()
                .email("child" + id + "@example.com")
                .lastName("山田")
                .firstName("子" + id)
                .displayName(displayName)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .birthDate(birthDate)
                .countryCode(countryCode)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("switchAllowed で children / blockedChildren に分離する")
    void splitsBySwitchAllowed() {
        UserEntity elementary = child(11L, "小学生の子", "2014-04-02", "JP"); // 2026-04-01 時点で切替可
        UserEntity juniorHigh = child(12L, "中学生の子", "2013-04-02", "JP"); // 2026-04-01 時点で封印

        given(parentalConsentService.listApprovedChildUserIds(GUARDIAN_ID))
                .willReturn(List.of(11L, 12L));
        given(careLinkService.listActiveParentWatchedRecipientIds(GUARDIAN_ID))
                .willReturn(List.of());
        given(userRepository.findByIdIn(anyCollection()))
                .willReturn(List.of(elementary, juniorHigh));

        SwitchableChildrenResponse response = service.listSwitchableChildren(GUARDIAN_ID);

        assertThat(response.children()).hasSize(1);
        assertThat(response.children().get(0).childUserId()).isEqualTo(11L);
        assertThat(response.children().get(0).displayName()).isEqualTo("小学生の子");
        assertThat(response.children().get(0).stageKey()).isEqualTo("elementary");
        assertThat(response.children().get(0).switchAllowed()).isTrue();

        assertThat(response.blockedChildren()).hasSize(1);
        assertThat(response.blockedChildren().get(0).childUserId()).isEqualTo(12L);
        assertThat(response.blockedChildren().get(0).stageKey()).isEqualTo("junior_high");
        assertThat(response.blockedChildren().get(0).switchAllowed()).isFalse();
        assertThat(response.blockedChildren().get(0).reason()).isEqualTo("AGE_LOCKED");
    }

    @Test
    @DisplayName("parental_consent と care_links の 2 経路を和集合化（重複排除）")
    void unionsBothSources() {
        UserEntity viaConsent = child(21L, "同意経路の子", "2018-05-05", "JP");
        UserEntity viaCare = child(22L, "ケア経路の子", "2019-06-06", "JP");

        // 21L は両経路に出現（重複）。findByIdIn には 21L,22L の和集合が渡る。
        given(parentalConsentService.listApprovedChildUserIds(GUARDIAN_ID))
                .willReturn(List.of(21L));
        given(careLinkService.listActiveParentWatchedRecipientIds(GUARDIAN_ID))
                .willReturn(List.of(21L, 22L));
        given(userRepository.findByIdIn(anyCollection()))
                .willAnswer(inv -> {
                    Collection<Long> ids = inv.getArgument(0);
                    // 和集合（重複排除）が渡ることを検証する。
                    assertThat(Set.copyOf(ids)).containsExactlyInAnyOrder(21L, 22L);
                    return List.of(viaConsent, viaCare);
                });

        SwitchableChildrenResponse response = service.listSwitchableChildren(GUARDIAN_ID);

        assertThat(response.children()).extracting(c -> c.childUserId())
                .containsExactlyInAnyOrder(21L, 22L);
        assertThat(response.blockedChildren()).isEmpty();
    }

    @Test
    @DisplayName("無関係ユーザー（リンクなし）は一切含まれない")
    void excludesUnrelatedUsers() {
        given(parentalConsentService.listApprovedChildUserIds(GUARDIAN_ID))
                .willReturn(List.of());
        given(careLinkService.listActiveParentWatchedRecipientIds(GUARDIAN_ID))
                .willReturn(List.of());

        SwitchableChildrenResponse response = service.listSwitchableChildren(GUARDIAN_ID);

        assertThat(response.children()).isEmpty();
        assertThat(response.blockedChildren()).isEmpty();
        // 候補が無ければユーザーロードも走らない。
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).findByIdIn(anyCollection());
    }

    @Test
    @DisplayName("未対応国の子はフォールバック（満13歳封印）で判定される")
    void unsupportedCountryUsesFallback() {
        // 2013-03-01 生まれ・国コード US（未対応）。2026-04-01 時点で満13歳到達済 → 封印。
        UserEntity usChild = child(31L, "海外の子", "2013-03-01", "US");
        given(parentalConsentService.listApprovedChildUserIds(GUARDIAN_ID))
                .willReturn(List.of(31L));
        given(careLinkService.listActiveParentWatchedRecipientIds(GUARDIAN_ID))
                .willReturn(List.of());
        given(userRepository.findByIdIn(anyCollection()))
                .willReturn(List.of(usChild));

        SwitchableChildrenResponse response = service.listSwitchableChildren(GUARDIAN_ID);

        assertThat(response.children()).isEmpty();
        assertThat(response.blockedChildren()).hasSize(1);
        assertThat(response.blockedChildren().get(0).stageKey()).isEqualTo("independent");
    }

    @Test
    @DisplayName("birthDate 欠落の子は安全側で封印する（症状を隠さない）")
    void missingBirthDateIsBlocked() {
        UserEntity noBirth = child(41L, "生年月日なしの子", null, "JP");
        given(parentalConsentService.listApprovedChildUserIds(GUARDIAN_ID))
                .willReturn(List.of(41L));
        given(careLinkService.listActiveParentWatchedRecipientIds(GUARDIAN_ID))
                .willReturn(List.of());
        given(userRepository.findByIdIn(anyCollection()))
                .willReturn(List.of(noBirth));

        SwitchableChildrenResponse response = service.listSwitchableChildren(GUARDIAN_ID);

        assertThat(response.children()).isEmpty();
        assertThat(response.blockedChildren()).hasSize(1);
        assertThat(response.blockedChildren().get(0).switchAllowed()).isFalse();
        assertThat(response.blockedChildren().get(0).reason()).isEqualTo("AGE_LOCKED");
    }

    @Test
    @DisplayName("自分自身が子/ケア対象として登録されていても結果に保護者本人を含まない")
    void guardianIsExcludedFromChildren() {
        // 自己リンク（guardianUserId = 100L が子として登録されている異常系）。
        UserEntity selfAsChild = child(GUARDIAN_ID, "自分自身", "2015-01-01", "JP");
        UserEntity legitimateChild = child(51L, "正当な子", "2016-02-02", "JP");

        given(parentalConsentService.listApprovedChildUserIds(GUARDIAN_ID))
                .willReturn(List.of(GUARDIAN_ID, 51L));
        given(careLinkService.listActiveParentWatchedRecipientIds(GUARDIAN_ID))
                .willReturn(List.of(GUARDIAN_ID));
        given(userRepository.findByIdIn(anyCollection()))
                .willReturn(List.of(legitimateChild));

        SwitchableChildrenResponse response = service.listSwitchableChildren(GUARDIAN_ID);

        // 保護者本人（100L）は children にも blockedChildren にも現れない。
        assertThat(response.children())
                .extracting(c -> c.childUserId())
                .doesNotContain(GUARDIAN_ID);
        assertThat(response.blockedChildren())
                .extracting(b -> b.childUserId())
                .doesNotContain(GUARDIAN_ID);
        // 正当な子は切替可（2026-04-01 時点で十分幼い）。
        assertThat(response.children())
                .extracting(c -> c.childUserId())
                .contains(51L);
    }

    @Test
    @DisplayName("birthDate が不正フォーマット/復号失敗で解決不能な子は blocked 側（switchAllowed=false・stageKey=independent）に倒れる")
    void invalidBirthDateFormatIsBlockedWithIndependentKey() {
        // 正常に復号できても ISO-8601 でないフォーマット（例: "20200101" や "2020/01/01"）は解決不能扱い。
        UserEntity badFormat = child(61L, "日付フォーマット不正の子", "2020/01/01", "JP");
        UserEntity nullBirth = child(62L, "birthDateがnullの子", null, "JP");

        given(parentalConsentService.listApprovedChildUserIds(GUARDIAN_ID))
                .willReturn(List.of(61L, 62L));
        given(careLinkService.listActiveParentWatchedRecipientIds(GUARDIAN_ID))
                .willReturn(List.of());
        given(userRepository.findByIdIn(anyCollection()))
                .willReturn(List.of(badFormat, nullBirth));

        SwitchableChildrenResponse response = service.listSwitchableChildren(GUARDIAN_ID);

        assertThat(response.children()).isEmpty();
        assertThat(response.blockedChildren()).hasSize(2);
        // 両者とも switchAllowed=false・stageKey="independent"（i18n キーとして有効な値）。
        assertThat(response.blockedChildren())
                .allSatisfy(b -> {
                    assertThat(b.switchAllowed()).isFalse();
                    assertThat(b.stageKey()).isEqualTo("independent");
                    assertThat(b.reason()).isEqualTo("AGE_LOCKED");
                });
    }
}
