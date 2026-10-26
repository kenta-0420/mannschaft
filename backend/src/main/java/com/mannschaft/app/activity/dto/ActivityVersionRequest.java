package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** 新画面の競合検出用。旧クライアントの本文なし公開は引き続き受け付ける。 */
public record ActivityVersionRequest(@PositiveOrZero Long version) {}
