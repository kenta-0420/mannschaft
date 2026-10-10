package com.mannschaft.app.diagnosis.dto;



/** 診断の回答。数値型と範囲はHTTP形式検査で確認する。 */
public record DiagnosisAnswer(String questionId, int value) {}
