package com.mannschaft.app.diagnosis;

import com.mannschaft.app.diagnosis.dto.DiagnosisQuestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** AC52/69: 六軸回答境界・本人同点解消の先行試練。 */
class DiagnosisScoringServiceTest {
    private final DiagnosisScoringService service = new DiagnosisScoringService();

    private List<DiagnosisQuestion> questions() {
        List<DiagnosisQuestion> result = new ArrayList<>();
        for (DiagnosisAxis axis : DiagnosisAxis.values()) {
            for (int n = 0; n < 4; n++) {
                result.add(new DiagnosisQuestion(axis.name() + n, axis, n % 2 == 0 ? 1 : -1, Map.of("ja", "試験設問")));
            }
        }
        return result;
    }
    private Map<String, Integer> answers(int value) {
        Map<String, Integer> values = new HashMap<>();
        questions().forEach(q -> values.put(q.id(), value));
        return values;
    }
    @Test @DisplayName("UT52/69: 全24回答3は六軸とも同点で型を確定しない")
    void 中立回答は六軸の本人選択を要求() {
        var score = service.score(questions(), answers(3), Map.of());
        assertThat(score.tiedAxes()).containsExactly(DiagnosisAxis.values());
        assertThat(score.typeCode()).isNull();
    }
    @Test @DisplayName("UT69: 本人の六つの二択だけで64型を決定する")
    void 本人同点回答で六十四型を固定() {
        for (int value = 0; value < 64; value++) {
            Map<DiagnosisAxis, Integer> tie = new HashMap<>();
            for (int i = 0; i < 6; i++) tie.put(DiagnosisAxis.values()[i], (value >> (5 - i)) & 1);
            assertThat(service.score(questions(), answers(3), tie).typeCode())
                    .isEqualTo(String.format("%6s", Integer.toBinaryString(value)).replace(' ', '0'));
        }
    }
    @Test @DisplayName("UT52: 正逆方向の境界1と5を採点し非同点への二択を拒否")
    void 設問の正逆方向を採点() {
        Map<String, Integer> values = answers(3);
        questions().forEach(q -> values.put(q.id(), q.polarity() == 1 ? 5 : 1));
        assertThat(service.score(questions(), values, Map.of()).typeCode()).isEqualTo("111111");
        assertThatThrownBy(() -> service.score(questions(), values, Map.of(DiagnosisAxis.FOCUS_VARIETY, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test @DisplayName("UT52: 23/25問、未知設問、0/6/nullを拒否")
    void 欠損未知設問と範囲外回答を拒否() {
        Map<String, Integer> values = answers(3);
        values.remove(questions().getFirst().id());
        Map<String, Integer> incomplete = values;
        assertThatThrownBy(() -> service.score(questions(), incomplete, Map.of())).isInstanceOf(IllegalArgumentException.class);
        values = answers(3); values.put("unknown", 3);
        Map<String, Integer> extra = values;
        assertThatThrownBy(() -> service.score(questions(), extra, Map.of())).isInstanceOf(IllegalArgumentException.class);
        for (Integer invalid : new Integer[]{0, 6, null}) {
            Map<String, Integer> bad = answers(3); bad.put(questions().getFirst().id(), invalid);
            assertThatThrownBy(() -> service.score(questions(), bad, Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test @DisplayName("UT69: 部分同点回答では未完了、二択範囲外と無効masterを拒否")
    void 同点回答の欠損と無効マスターを拒否() {
        assertThat(service.score(questions(), answers(3), Map.of(DiagnosisAxis.FOCUS_VARIETY, 0)).typeCode()).isNull();
        assertThatThrownBy(() -> service.score(questions(), answers(3), Map.of(DiagnosisAxis.FOCUS_VARIETY, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        var bad = new ArrayList<>(questions()); bad.set(0, bad.get(1));
        assertThatThrownBy(() -> service.score(bad, answers(3), Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
