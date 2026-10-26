package com.mannschaft.app.quickmemo.dto;

/**
 * メモをTODOへ変換したレスポンス。
 */
public record ConvertToTodoResponse(Long memoId, Long todoId, String memoStatus) {}
