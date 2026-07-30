package com.mannschaft.app.common.timezone;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserTimezoneFilter} の単体テスト（Issue #2508 / AC-7）。
 *
 * <p>「どの経路で解決済みの印（{@link TimezoneContextHolder#isResolved()}）を付けるか」を固定する。
 * 印を付ける条件を誤ると、{@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer} が
 * バッチ・未認証の既定 UTC をユーザー TZ と誤認し、オフセット無し入力を −9 時間ずらして保持してしまう。</p>
 *
 * <p>ホルダーはフィルターの finally でクリアされるため、観測は必ず
 * {@link FilterChain} の内側（= リクエスト処理中）で行う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserTimezoneFilter 単体テスト")
class UserTimezoneFilterTest {

    private static final Long USER_ID = 90209L;

    @Mock
    private UserTimezoneCache userTimezoneCache;

    private UserTimezoneFilter filter;

    /** チェーン内側（リクエスト処理中）で観測したホルダーの状態 */
    private ZoneId observedZone;
    private Boolean observedResolved;

    @BeforeEach
    void setUp() {
        filter = new UserTimezoneFilter();
        ReflectionTestUtils.setField(filter, "userTimezoneCache", userTimezoneCache);
        observedZone = null;
        observedResolved = null;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TimezoneContextHolder.clear();
    }

    /** フィルターを 1 回通し、チェーン内側でホルダーの状態を捕捉する */
    private void invokeFilter() throws Exception {
        FilterChain chain = (req, res) -> {
            observedZone = TimezoneContextHolder.get();
            observedResolved = TimezoneContextHolder.isResolved();
        };
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    }

    private void authenticateAs(String principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    // ------------------------------------------------------------------
    // 解決済みの印を付ける経路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("認証済み＋キャッシュから正常な TZ を取得できたら解決済みの印を付ける")
    void 認証済み_正常TZ_解決済み() throws Exception {
        // Given
        authenticateAs(String.valueOf(USER_ID));
        when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("America/Los_Angeles");

        // When
        invokeFilter();

        // Then
        assertThat(observedZone).isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(observedResolved).isTrue();
    }

    @Test
    @DisplayName("認証済み＋Asia/Tokyo（DB 既定値）でも解決済みの印を付ける")
    void 認証済み_Tokyo_解決済み() throws Exception {
        // Given
        authenticateAs(String.valueOf(USER_ID));
        when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("Asia/Tokyo");

        // When
        invokeFilter();

        // Then
        assertThat(observedZone).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(observedResolved).isTrue();
    }

    @Test
    @DisplayName("認証済み＋TZ が null なら Asia/Tokyo にフォールバックし解決済みの印を付ける")
    void 認証済み_TZがnull_Tokyoで解決済み() throws Exception {
        // Given
        authenticateAs(String.valueOf(USER_ID));
        when(userTimezoneCache.getTimezone(USER_ID)).thenReturn(null);

        // When
        invokeFilter();

        // Then: users.timezone は NOT NULL DEFAULT 'Asia/Tokyo' なのでフォールバック先は DB 既定値と一致する。
        // ここで印を落として UTC を積むとシリアライザ出力が +09:00 から Z に変わってしまうため、印は付ける。
        assertThat(observedZone).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(observedResolved).isTrue();
    }

    @Test
    @DisplayName("認証済み＋TZ が空文字なら Asia/Tokyo にフォールバックし解決済みの印を付ける")
    void 認証済み_TZが空文字_Tokyoで解決済み() throws Exception {
        // Given
        authenticateAs(String.valueOf(USER_ID));
        when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("  ");

        // When
        invokeFilter();

        // Then
        assertThat(observedZone).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(observedResolved).isTrue();
    }

    @Test
    @DisplayName("認証済み＋不正な TZ 文字列なら Asia/Tokyo にフォールバックし解決済みの印を付ける")
    void 認証済み_不正TZ_Tokyoで解決済み() throws Exception {
        // Given
        authenticateAs(String.valueOf(USER_ID));
        when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("Mars/Olympus_Mons");

        // When
        invokeFilter();

        // Then: フォールバック先（Asia/Tokyo）は未解決時の解釈とも一致するため、
        // 印の有無でデシリアライズ結果は変わらない。既存のシリアライザ出力を保つ側に寄せる。
        assertThat(observedZone).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(observedResolved).isTrue();
    }

    // ------------------------------------------------------------------
    // 解決済みの印を付けない経路（未解決 = UTC を印なしで積む）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未認証（Authentication なし）は UTC を印なしで積む")
    void 未認証_未解決UTC() throws Exception {
        // Given: SecurityContext に Authentication をセットしない

        // When
        invokeFilter();

        // Then
        assertThat(observedZone).isEqualTo(ZoneOffset.UTC);
        assertThat(observedResolved).isFalse();
        verify(userTimezoneCache, never()).getTimezone(anyLong());
    }

    @Test
    @DisplayName("匿名認証（principal が userId でない）は UTC を印なしで積む")
    void 匿名認証_未解決UTC() throws Exception {
        // Given
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        // When
        invokeFilter();

        // Then: Long.parseLong に失敗するため未解決
        assertThat(observedZone).isEqualTo(ZoneOffset.UTC);
        assertThat(observedResolved).isFalse();
        verify(userTimezoneCache, never()).getTimezone(anyLong());
    }

    @Test
    @DisplayName("userTimezoneCache 未注入（@WebMvcTest スライス）は NPE にならず UTC を印なしで積む")
    void キャッシュ未注入_未解決UTC() throws Exception {
        // Given: 認証済みだがキャッシュ Bean が存在しない（従来は NPE になり得た経路）
        ReflectionTestUtils.setField(filter, "userTimezoneCache", null);
        authenticateAs(String.valueOf(USER_ID));

        // When / Then: 例外を投げずに未解決へ落ちる
        assertThatCode(this::invokeFilter).doesNotThrowAnyException();
        assertThat(observedZone).isEqualTo(ZoneOffset.UTC);
        assertThat(observedResolved).isFalse();
    }

    // ------------------------------------------------------------------
    // 後片付け（スレッドプール汚染防止）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("リクエスト終了後は ZoneId も解決済みの印もクリアされている")
    void 終了後にクリアされる() throws Exception {
        // Given
        authenticateAs(String.valueOf(USER_ID));
        when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("America/Los_Angeles");

        // When
        invokeFilter();

        // Then: チェーン内側では解決済みだったが、終了後は未解決の既定状態に戻る
        assertThat(observedResolved).isTrue();
        assertThat(TimezoneContextHolder.isResolved()).isFalse();
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("チェーンが例外を投げてもホルダーはクリアされる")
    void チェーン例外時もクリアされる() throws Exception {
        // Given
        authenticateAs(String.valueOf(USER_ID));
        when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("America/Los_Angeles");
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("想定内の失敗");
        };

        // When
        assertThatCode(() -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain))
                .isInstanceOf(IllegalStateException.class);

        // Then
        assertThat(TimezoneContextHolder.isResolved()).isFalse();
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------
    // 未解決 ZoneId の実体（ZoneOffset.UTC）を固定する
    //
    // 改修前は ZoneId.of("UTC")（ZoneRegion・id="UTC"）を積んでいたが、これは
    // ZoneOffset.UTC（id="Z"）と equals では一致しない。「ホルダー未セット時の既定と同じ」という
    // 不変条件を謳いながら実体が違う状態は、その不変条件を信じて等価比較を書いた者を欺くため、
    // ZoneOffset.UTC に統一した。以下はその統一を固定し、かつ挙動が変わらないことを示すテスト。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未解決時に積む ZoneId は、ホルダー未セット時の既定値と equals でも一致する")
    void 未解決ゾーンはホルダー未セット既定とequals一致() throws Exception {
        // Given: 未認証（未解決経路）
        // When: フィルター通過中の値を捕捉する
        invokeFilter();

        // Then: クリア後（＝未セット）にホルダーが返す既定値と equals で一致する。
        // ここが崩れると「未解決 ≡ 未セット」という前提に基づくコードが静かに壊れる
        TimezoneContextHolder.clear();
        assertThat(observedZone)
                .as("フィルターの未解決値とホルダー未セット既定は同一であること")
                .isEqualTo(TimezoneContextHolder.get());
        assertThat(observedZone).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("ZoneOffset.UTC と ZoneId.of(\"UTC\") は瞬間・日付計算が完全に一致する（統一が挙動を変えない証明）")
    void UTC二表現は計算結果が同一() {
        // Given: equals では一致しない二つの UTC 表現
        ZoneId zoneRegionUtc = ZoneId.of("UTC");
        assertThat(zoneRegionUtc)
                .as("equals では一致しない（だからこそ実体の統一が必要だった）")
                .isNotEqualTo(ZoneOffset.UTC);

        // When / Then: ゾーンを計算に使う限り結果は完全に一致する。
        // 依存箇所（LocalDate.now(zone) / withZoneSameInstant(zone) 等）は全てこの用途なので、
        // ZoneOffset.UTC への統一によって観測可能な挙動は変わらない
        LocalDateTime jstWallClock = LocalDateTime.of(2026, 5, 22, 9, 15, 20);
        assertThat(jstWallClock.atZone(ZoneId.of("Asia/Tokyo")).withZoneSameInstant(zoneRegionUtc).toLocalDateTime())
                .isEqualTo(jstWallClock.atZone(ZoneId.of("Asia/Tokyo")).withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime());
        assertThat(zoneRegionUtc.getRules().getOffset(Instant.EPOCH))
                .isEqualTo(ZoneOffset.UTC.getRules().getOffset(Instant.EPOCH));
        assertThat(LocalDate.now(zoneRegionUtc)).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }
}
