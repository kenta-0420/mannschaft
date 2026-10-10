package com.mannschaft.app.auth.dto;



/** 本人入力確認専用の最小情報。他のprofile応答へ出生情報を追加しない。 */
public record BirthProfileResponse(String lastName, String firstName, String lastNameKana,
        String firstNameKana, String birthDate, String revision) {}
