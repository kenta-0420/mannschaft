package com.mannschaft.app.auth.dto;



/** 形式確認後の本人出生情報補完。原情報は既存auth暗号化欄だけへ保存する。 */
public record BirthProfileUpdateRequest(String lastName, String firstName, String lastNameKana,
        String firstNameKana, String birthDate, long revision) {}
