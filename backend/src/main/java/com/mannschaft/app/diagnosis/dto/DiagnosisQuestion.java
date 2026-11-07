package com.mannschaft.app.diagnosis.dto;

import com.mannschaft.app.diagnosis.DiagnosisAxis;
import java.util.Map;

/** 不変の設問・方向・六言語表示スナップショット。 */
public record DiagnosisQuestion(String id, DiagnosisAxis axis, int polarity, Map<String, String> text) {
    public DiagnosisQuestion { text = Map.copyOf(text); }
}
