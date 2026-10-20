package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.service.BirthStyleCalculator.BirthNumbers;

/** 認証境界で確認済みの派生数と計算元版。本人原情報を他ドメインへ渡さない内部値。 */
public record ConfirmedBirthNumbers(BirthNumbers numbers, long profileRevision) {}
