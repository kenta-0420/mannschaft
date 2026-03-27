package com.mannschaft.app.seal;

import com.mannschaft.app.seal.service.SealGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SealGenerator} の単体テスト。
 * SVG生成・ハッシュ計算・文字数別レイアウトを検証する。
 */
@DisplayName("SealGenerator 単体テスト")
class SealGeneratorTest {

    private SealGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SealGenerator();
    }

    // ----------------------------------------
    // generateSvg
    // ----------------------------------------
    @Nested
    @DisplayName("generateSvg")
    class GenerateSvg {

        @Test
        @DisplayName("正常系: 1文字の場合 SVG が生成される (横書き, fontSize=40)")
        void 一文字の場合() {
            String svg = generator.generateSvg("山", SealVariant.LAST_NAME);

            assertThat(svg).startsWith("<svg");
            assertThat(svg).endsWith("</svg>");
            assertThat(svg).contains("<circle");
            assertThat(svg).contains("山");
            assertThat(svg).contains("font-size=\"40\"");
        }

        @Test
        @DisplayName("正常系: 2文字の場合 横書き (fontSize=28)")
        void 二文字の場合() {
            String svg = generator.generateSvg("田中", SealVariant.LAST_NAME);

            assertThat(svg).contains("田中");
            assertThat(svg).contains("font-size=\"28\"");
        }

        @Test
        @DisplayName("正常系: 3文字の場合 縦書き (fontSize=22, 3要素)")
        void 三文字の場合() {
            String svg = generator.generateSvg("田中一", SealVariant.FULL_NAME);

            assertThat(svg).contains("font-size=\"22\"");
            // 3文字それぞれが別の <text> タグで出力される
            assertThat(svg).contains(">田<");
            assertThat(svg).contains(">中<");
            assertThat(svg).contains(">一<");
        }

        @Test
        @DisplayName("正常系: 4文字の場合 縦書き (fontSize=22)")
        void 四文字の場合() {
            String svg = generator.generateSvg("田中太郎", SealVariant.FULL_NAME);

            assertThat(svg).contains("font-size=\"22\"");
            assertThat(svg).contains(">田<");
            assertThat(svg).contains(">郎<");
        }

        @Test
        @DisplayName("正常系: 5文字以上の場合 2列縦書き (fontSize=18)")
        void 五文字以上の場合() {
            String svg = generator.generateSvg("山田花子代", SealVariant.FULL_NAME);

            assertThat(svg).contains("font-size=\"18\"");
            assertThat(svg).contains(">山<");
        }

        @Test
        @DisplayName("正常系: 6文字の場合 2列縦書き")
        void 六文字の場合() {
            String svg = generator.generateSvg("山田太郎次郎", SealVariant.FULL_NAME);

            assertThat(svg).contains("font-size=\"18\"");
            assertThat(svg).contains(">山<");
            assertThat(svg).contains(">郎<");
        }

        @Test
        @DisplayName("正常系: XML特殊文字がエスケープされる")
        void XML特殊文字エスケープ() {
            // &, <, >, " を含む文字列
            String svg = generator.generateSvg("A&B", SealVariant.LAST_NAME);

            // SVGでは文字が行分割されるため、&がエスケープされていることを確認
            assertThat(svg).contains("&amp;");
            assertThat(svg).doesNotContain("&B>"); // 生の&が含まれないこと
        }

        @Test
        @DisplayName("正常系: < > \" のエスケープ")
        void 山カッコとダブルクォートエスケープ() {
            String svg = generator.generateSvg("<A\"B>", SealVariant.LAST_NAME);

            assertThat(svg).contains("&lt;");
            assertThat(svg).contains("&gt;");
            assertThat(svg).contains("&quot;");
        }

        @Test
        @DisplayName("正常系: SVG には svgns 属性が含まれる")
        void SVGヘッダー確認() {
            String svg = generator.generateSvg("鈴木", SealVariant.LAST_NAME);

            assertThat(svg).contains("xmlns=\"http://www.w3.org/2000/svg\"");
            assertThat(svg).contains("width=\"100\"");
            assertThat(svg).contains("height=\"100\"");
        }

        @Test
        @DisplayName("正常系: 印鑑の色が赤 (#D12B2B) で設定される")
        void 印鑑の色確認() {
            String svg = generator.generateSvg("佐藤", SealVariant.LAST_NAME);

            assertThat(svg).contains("#D12B2B");
        }
    }

    // ----------------------------------------
    // computeHash
    // ----------------------------------------
    @Nested
    @DisplayName("computeHash")
    class ComputeHash {

        @Test
        @DisplayName("正常系: SHA-256 ハッシュが64文字の16進数で返される")
        void SHA256ハッシュ生成() {
            String svgData = "<svg>test</svg>";
            String hash = generator.computeHash(svgData);

            assertThat(hash).hasSize(64);
            assertThat(hash).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("正常系: 同じ入力は同じハッシュを返す")
        void 同じ入力は同じハッシュ() {
            String svgData = "<svg>deterministic</svg>";
            String hash1 = generator.computeHash(svgData);
            String hash2 = generator.computeHash(svgData);

            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("正常系: 異なる入力は異なるハッシュを返す")
        void 異なる入力は異なるハッシュ() {
            String hash1 = generator.computeHash("<svg>A</svg>");
            String hash2 = generator.computeHash("<svg>B</svg>");

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("正常系: 空文字列のハッシュが返される")
        void 空文字列のハッシュ() {
            String hash = generator.computeHash("");

            assertThat(hash).hasSize(64);
            // SHA-256("") の既知の値
            assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }

        @Test
        @DisplayName("正常系: 実際のSVGデータのハッシュを計算できる")
        void 実際のSVGデータのハッシュ() {
            String svg = generator.generateSvg("田中", SealVariant.LAST_NAME);
            String hash = generator.computeHash(svg);

            assertThat(hash).hasSize(64);
            assertThat(hash).isNotEmpty();
        }
    }
}
