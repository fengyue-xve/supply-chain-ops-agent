package com.demo.supplyagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档位纯规则边界（不依赖 Spring/DB）：
 * <=0 -> out；<20 -> low；<=200 -> ok；>200 -> high。
 */
class StockLevelRulesTest {

    @Test
    void outWhenZeroOrNegative() {
        assertThat(StockLevelRules.of(0)).isEqualTo("out");
        assertThat(StockLevelRules.of(-5)).isEqualTo("out");
    }

    @Test
    void lowWhenBelowTwenty() {
        assertThat(StockLevelRules.of(1)).isEqualTo("low");
        assertThat(StockLevelRules.of(19)).isEqualTo("low");
    }

    @Test
    void okFromTwentyToTwoHundred() {
        assertThat(StockLevelRules.of(20)).isEqualTo("ok");
        assertThat(StockLevelRules.of(200)).isEqualTo("ok");
    }

    @Test
    void highWhenAboveTwoHundred() {
        assertThat(StockLevelRules.of(201)).isEqualTo("high");
        assertThat(StockLevelRules.of(999)).isEqualTo("high");
    }
}
