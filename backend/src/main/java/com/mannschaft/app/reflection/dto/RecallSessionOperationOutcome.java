package com.mannschaft.app.reflection.dto;

/** native commit の新完了だけを源側が識別する。本文や資格を報酬事実へ推測しない。 */
public record RecallSessionOperationOutcome(RecallSessionResponse response, boolean newCompletion) { }
