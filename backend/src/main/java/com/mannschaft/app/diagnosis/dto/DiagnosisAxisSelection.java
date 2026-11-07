package com.mannschaft.app.diagnosis.dto;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/** 完成時の選択側と、開始時に保存した両極の表示。回答原本は含めない。 */
public record DiagnosisAxisSelection(@Schema(minimum = "0", maximum = "1") int side,
        Map<String, String> zero, Map<String, String> one) {
    public DiagnosisAxisSelection {
        zero = Map.copyOf(zero);
        one = Map.copyOf(one);
    }
}
