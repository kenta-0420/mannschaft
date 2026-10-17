package com.mannschaft.app.budget.service;

/** 認可済み年度内の履歴表示に使う読み取り専用費目。新規入力には使用しない。 */
record HistoricalBudgetCategory(Long id, String name, String categoryType, boolean deleted) {
}
