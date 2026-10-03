package com.mannschaft.app.common.featuregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code FEATURE_GATE_001} の HTTP ステータス宣言テスト（Gate 基盤工事③・試練 / 受け入れ条件 AC-7）。
 *
 * <p>{@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に<b>未登録</b>のコードは
 * {@code Severity} 既定（{@code WARN} なら 400）へ静かにフォールバックする。
 * {@link FeatureGateErrorCode#FEATURE_GATE_001} は {@code Severity.WARN} なので、
 * 登録を忘れると宣言（403）と実挙動（400）が乖離したまま緑になる。
 * よって<b>登録そのもの</b>をここで固定する（実 HTTP が実際に 403 になることは
 * {@link FeatureGateAspectIT} が実フィルタチェーン越しに裏取りする）。</p>
 *
 * <p>マスター裁可: <b>403 FORBIDDEN</b>（404 でも 422 でもない）。</p>
 */
@DisplayName("FEATURE_GATE_001 の HTTP ステータス宣言（Gate基盤工事③ AC-7）")
class FeatureGateErrorCodeStatusTest {

    private static final Path HANDLER = Paths.get(
            "src", "main", "java", "com", "mannschaft", "app", "common", "GlobalExceptionHandler.java");

    @Test
    @DisplayName("(AC-7) ERROR_CODE_STATUS_MAP に FEATURE_GATE_001 -> FORBIDDEN が登録されていること")
    void ac7_FEATURE_GATE_001は403に登録されている() throws IOException {
        assertThat(Files.isRegularFile(HANDLER))
                .as("GlobalExceptionHandler が見つからない: " + HANDLER.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）")
                .isTrue();

        String source = Files.readString(HANDLER, StandardCharsets.UTF_8);

        assertThat(source)
                .as("ERROR_CODE_STATUS_MAP に FEATURE_GATE_001 が未登録である。"
                        + "未登録コードは Severity.WARN 既定の 400 へ静かに落ち、"
                        + "宣言（403）と実挙動が乖離する")
                .contains("FEATURE_GATE_001");

        assertThat(source.replaceAll("\\s+", ""))
                .as("FEATURE_GATE_001 は HttpStatus.FORBIDDEN（403）にマップすること。"
                        + "マスター裁可済みで、404/422 ではない")
                .contains("Map.entry(\"FEATURE_GATE_001\",HttpStatus.FORBIDDEN)");
    }

    @Test
    @DisplayName("(AC-7 前提) FEATURE_GATE_001 のコード文字列と Severity が想定どおりであること")
    void ac7_エラーコードの宣言が想定どおり() {
        assertThat(FeatureGateErrorCode.FEATURE_GATE_001.getCode()).isEqualTo("FEATURE_GATE_001");
        assertThat(FeatureGateErrorCode.FEATURE_GATE_001.getSeverity())
                .isEqualTo(com.mannschaft.app.common.ErrorCode.Severity.WARN);
    }
}
