package com.mannschaft.app.diagnosis.dto;



/** 出生原情報を含まない独自数秘の派生値。 */
public record DiagnosisNumberSummary(int lifePathNumber, int nameNumber, int dateSum, int nameSum) {}
