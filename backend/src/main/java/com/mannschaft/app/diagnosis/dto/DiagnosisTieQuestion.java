package com.mannschaft.app.diagnosis.dto;

import com.mannschaft.app.diagnosis.DiagnosisAxis;
import java.util.Map;

/** 同点となった軸だけの本人二択。未承認原稿は開発限定。 */
public record DiagnosisTieQuestion(DiagnosisAxis axisId, Map<String,String> zero, Map<String,String> one) {}
