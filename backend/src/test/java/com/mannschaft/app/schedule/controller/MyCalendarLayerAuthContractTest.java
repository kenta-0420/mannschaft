package com.mannschaft.app.schedule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.19 W1-c — レイヤー API 3 本の認証必須性を<b>機構として</b>固定する番人（AC-16 / AC-17）。
 *
 * <p>本来 AC-16/17 は実フィルタチェーンを通す IT（{@code @AutoConfigureMockMvc} ＋
 * {@code AbstractMySqlIntegrationTest}）で確かめるのが筋だが、その形は Docker が無い環境で
 * <b>丸ごと skip され「走っていないのに緑」になる</b>。認証必須性は
 * {@code SecurityConfig} の permitAll 列挙に当該パスが<b>載っていないこと</b>で決まるため、
 * ここではその不変条件を Docker 非依存で固定する（＝いつでも必ず走る番人）。</p>
 *
 * <p>非所属スコープの 403（AC-16 後段）は {@code CalendarLayerServiceTest} が
 * {@code SCHEDULE_101} の送出として固定している。</p>
 */
@DisplayName("F03.19 レイヤー API の認証必須性（AC-16 / AC-17）")
class MyCalendarLayerAuthContractTest {

    private static final Path SECURITY_CONFIG = Path.of(
            "src/main/java/com/mannschaft/app/config/SecurityConfig.java");
    private static final Path CONTROLLER = Path.of(
            "src/main/java/com/mannschaft/app/schedule/controller/MyCalendarLayerController.java");

    @Test
    @DisplayName("【機構の生存証明】検査対象のソースが実在し permitAll 列挙を含む")
    void guardActuallyReadsSomething() throws IOException {
        assertThat(SECURITY_CONFIG).exists();
        assertThat(CONTROLLER).exists();
        assertThat(read(SECURITY_CONFIG)).contains("permitAll()");
    }

    @Test
    @DisplayName("AC-17 /me/calendar-layers は permitAll に列挙されていない（未認証は 401）")
    void calendarLayerPathIsNotPermitAll() throws IOException {
        List<String> permitAllLines = read(SECURITY_CONFIG).lines()
                .filter(line -> line.contains("permitAll"))
                .toList();

        assertThat(permitAllLines)
                .as("permitAll 行に calendar-layers が現れてはならない（現れたら未認証で 200 が返る）")
                .noneMatch(line -> line.contains("calendar-layers"));
    }

    @Test
    @DisplayName("AC-16 3 本すべて（GET / PATCH / DELETE）が同じ本人限定パス配下にある")
    void allThreeEndpointsShareTheAuthenticatedBasePath() throws IOException {
        String source = read(CONTROLLER);

        assertThat(source).contains("@RequestMapping(\"/api/v1/me/calendar-layers\")");
        assertThat(source).contains("@GetMapping");
        assertThat(source).contains("@PatchMapping");
        assertThat(source).contains("@DeleteMapping");
        // 認可の主体は常に認証主体（パス・クエリから userId を受け取らない・§4.1）。
        assertThat(source).contains("SecurityUtils.getCurrentUserId()");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
