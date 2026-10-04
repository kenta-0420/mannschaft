package com.mannschaft.app.ranch;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import com.ibm.icu.util.VersionInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** BE/FE共有のraw Unicode 17 fixtureを、UTF-16offsetではなくcode point segment配列で検証する。 */
class RanchGraphemeCompatibilityTest {
    private static final Path FIXTURE = Path.of("..", "test-fixtures", "unicode", "17.0.0", "GraphemeBreakTest.txt");
    private static final String SHA256 = "e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec";

    @Test
    void ICU版とraw公式fixtureの全書記素境界が一致する() throws Exception {
        assertThat(VersionInfo.ICU_VERSION).isEqualTo(VersionInfo.getInstance(78, 3, 0, 0));
        assertThat(UCharacter.getUnicodeVersion()).isEqualTo(VersionInfo.getInstance(17, 0, 0, 0));
        byte[] raw = Files.readAllBytes(FIXTURE);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))).isEqualTo(SHA256);
        int cases = 0;
        int lineNumber = 0;
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
            lineNumber++;
            String specification = line.split("#", 2)[0].trim();
            if (specification.isEmpty()) {
                continue;
            }
            String[] tokens = specification.split("\\s+");
            assertThat(tokens[0]).isEqualTo("÷");
            assertThat(tokens[tokens.length - 1]).isEqualTo("÷");
            StringBuilder input = new StringBuilder();
            List<int[]> expected = new ArrayList<>();
            List<Integer> current = new ArrayList<>();
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i];
                if (i % 2 == 0) {
                    assertThat(token).isIn("÷", "×");
                    if (token.equals("÷") && !current.isEmpty()) {
                        expected.add(current.stream().mapToInt(Integer::intValue).toArray());
                        current.clear();
                    }
                } else {
                    int codePoint = Integer.parseInt(token, 16);
                    input.appendCodePoint(codePoint);
                    current.add(codePoint);
                }
            }
            assertThat(current).isEmpty();
            BreakIterator iterator = BreakIterator.getCharacterInstance(ULocale.ROOT);
            String text = input.toString();
            iterator.setText(text);
            List<int[]> actual = new ArrayList<>();
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
                actual.add(text.substring(start, end).codePoints().toArray());
                start = end;
            }
            assertThat(actual).as("raw Unicode 17 fixture line %s", lineNumber)
                    .usingRecursiveComparison().isEqualTo(expected);
            cases++;
        }
        assertThat(cases).isGreaterThan(700);
    }
}
