package com.mannschaft.app.auth.dto;



/** 原PIIを複製せず成功再送できる本人情報更新の版ACK。 */
public record BirthProfileUpdateResponse(String revision) {}
