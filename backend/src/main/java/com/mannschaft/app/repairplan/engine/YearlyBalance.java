package com.mannschaft.app.repairplan.engine;

import java.math.BigDecimal;

/** 1年分の収支サマリ（不変）。 */
public record YearlyBalance(
        int year,
        BigDecimal balance,
        BigDecimal income,
        BigDecimal plannedExpense,
        BigDecimal actualExpense
) {}
