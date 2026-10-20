package com.mannschaft.app.diagnosis.dto;

import com.mannschaft.app.diagnosis.DiagnosisAxis;

/** 本人同点二択の回答。対象回答版へ束縛する。 */
public record DiagnosisTieAnswer(DiagnosisAxis axisId, int value) {}
