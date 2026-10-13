package io.dl.coder.config;

/**
 * thinking 深度级别：off 不注入任何参数；low/medium/high 按厂商映射为具体生成参数。
 */
public enum ThinkingLevel {

    OFF, LOW, MEDIUM, HIGH;

    /** 解析级别名（大小写容错）；非法值抛异常并列出可选值（/thinking 命令用）。 */
    public static ThinkingLevel parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少级别，可选值: off | low | medium | high");
        }
        try {
            return ThinkingLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知 thinking 级别: " + value + "（可选 off | low | medium | high）");
        }
    }

    /** 宽松解析：null/空/非法一律回退 OFF，不抛异常（配置加载用）。 */
    public static ThinkingLevel parseOrOff(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return ThinkingLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }

    /** 小写名，配置落盘与终端展示统一用它。 */
    public String lowerName() {
        return name().toLowerCase();
    }
}
