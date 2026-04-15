package com.mannschaft.app.advertising.ranking;

import com.mannschaft.app.advertising.ranking.service.EquipmentNormalizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EquipmentNormalizationService} の単体テスト。
 * 備品名の正規化処理（全角→半角、括弧除去、記号除去、トリム）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EquipmentNormalizationService 単体テスト")
class EquipmentNormalizationServiceTest {

    @InjectMocks
    private EquipmentNormalizationService service;

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("正常系: 全角英数字が半角小文字に変換される")
        void 全角英数字が半角小文字に変換される() {
            // Given
            String input = "Ｗｈｉｔｅ Ｂｏａｒｄ";

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("white board");
        }

        @Test
        @DisplayName("正常系: 括弧内の補足説明が除去される（全角括弧）")
        void 括弧内補足が除去される_全角括弧() {
            // Given
            String input = "プロジェクター（EPSON）";

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("プロジェクター");
        }

        @Test
        @DisplayName("正常系: 括弧内の補足説明が除去される（半角括弧）")
        void 括弧内補足が除去される_半角括弧() {
            // Given
            String input = "プロジェクター(EPSON)";

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("プロジェクター");
        }

        @Test
        @DisplayName("正常系: 中点（・）などの記号が除去される")
        void 記号が除去される() {
            // Given
            String input = "テーピング・50mm";

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("テーピング50mm");
        }

        @Test
        @DisplayName("正常系: 先頭・末尾のスペースがトリムされる")
        void 先頭末尾スペースがトリムされる() {
            // Given
            String input = "  サッカーボール  ";

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("サッカーボール");
        }

        @Test
        @DisplayName("正常系: 連続スペースが1つのスペースに正規化される")
        void 連続スペースが正規化される() {
            // Given
            String input = "white   board";

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("white board");
        }

        @Test
        @DisplayName("正常系: null が渡された場合は空文字列が返る")
        void nullが渡された場合は空文字列が返る() {
            // When
            String result = service.normalize(null);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 空文字列が渡された場合は空文字列が返る")
        void 空文字列が渡された場合は空文字列が返る() {
            // When
            String result = service.normalize("");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: ブランク文字列が渡された場合は空文字列が返る")
        void ブランク文字列が渡された場合は空文字列が返る() {
            // When
            String result = service.normalize("   ");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 全角スペースが半角スペースに変換される")
        void 全角スペースが半角スペースに変換される() {
            // Given
            String input = "Ａ　Ｂ"; // 全角英字と全角スペース

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("a b");
        }

        @Test
        @DisplayName("正常系: ハッシュ記号（#）が除去される")
        void ハッシュ記号が除去される() {
            // Given
            String input = "ボール#5号";

            // When
            String result = service.normalize(input);

            // Then
            assertThat(result).isEqualTo("ボール5号");
        }

        @Test
        @DisplayName("正常系: 複数の正規化処理が組み合わせて適用される")
        void 複数の正規化処理が組み合わせて適用される() {
            // Given
            String input = "Ｐｒｏｊｅｃｔｏｒ（ＥＰＳＯＮ）  ";

            // When
            String result = service.normalize(input);

            // Then
            // 全角→半角 → 小文字 → 括弧内除去 → トリム
            assertThat(result).isEqualTo("projector");
        }
    }
}
