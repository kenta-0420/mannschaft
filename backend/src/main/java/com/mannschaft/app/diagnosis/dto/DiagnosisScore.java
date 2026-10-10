package com.mannschaft.app.diagnosis.dto;

import com.mannschaft.app.diagnosis.DiagnosisAxis;
import java.util.List;
import java.util.Map;

/** 採点済みの軸と本人追加選択を要する軸。未確定の型は返さない。 */
public record DiagnosisScore(Map<DiagnosisAxis, Integer> axes, List<DiagnosisAxis> tiedAxes, String typeCode) {
    public DiagnosisScore { axes = Map.copyOf(axes); tiedAxes = List.copyOf(tiedAxes); }
}
