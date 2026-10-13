package io.dl.coder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ThinkingLevel 解析规则（/thinking 命令用严格版，配置加载用宽松版）。 */
class ThinkingLevelTest {

    @Test
    void parse大小写与空白容错() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.parse("high"));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.parse(" HIGH "));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.parse("Low"));
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.parse("off"));
    }

    @Test
    void parse空值报缺少级别() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ThinkingLevel.parse(null)).getMessage().contains("缺少级别"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ThinkingLevel.parse("   ")).getMessage().contains("缺少级别"));
    }

    @Test
    void parse非法值报可选范围() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ThinkingLevel.parse("max"));
        assertTrue(e.getMessage().contains("未知"));
        assertTrue(e.getMessage().contains("off | low | medium | high"));
    }

    @Test
    void parseOrOff空与非法一律回退OFF() {
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.parseOrOff(null));
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.parseOrOff(""));
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.parseOrOff("garbage"));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.parseOrOff(" Medium "));
    }

    @Test
    void lowerName用于落盘与展示() {
        assertEquals("off", ThinkingLevel.OFF.lowerName());
        assertEquals("medium", ThinkingLevel.MEDIUM.lowerName());
        assertEquals("high", ThinkingLevel.HIGH.lowerName());
    }
}
