package com.fungus.farm.service;

import com.fungus.farm.domain.EnvironmentRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 各菇种环境适宜区间与越限判定，全系统唯一口径（仪表盘、催蕾校验、处置单对账共用）。
 * 返回顺序固定：温度、湿度、CO₂。
 */
public final class EnvironmentRules {

    public static final List<String> METRIC_NAMES = List.of("温度", "湿度", "CO₂");
    public static final int RECOVERY_REQUIRED = 6;
    public static final int TRIGGER_REQUIRED = 3;

    private EnvironmentRules() {
    }

    private static BigDecimal[] range(String species) {
        return switch (species) {
            // minTemp, maxTemp, minHumidity, maxHumidity, maxCo2
            case "金针菇" -> new BigDecimal[] {BigDecimal.valueOf(4), BigDecimal.valueOf(12), BigDecimal.valueOf(85), BigDecimal.valueOf(95), BigDecimal.valueOf(1200)};
            case "杏鲍菇" -> new BigDecimal[] {BigDecimal.valueOf(12), BigDecimal.valueOf(18), BigDecimal.valueOf(85), BigDecimal.valueOf(95), BigDecimal.valueOf(1500)};
            case "香菇" -> new BigDecimal[] {BigDecimal.valueOf(12), BigDecimal.valueOf(20), BigDecimal.valueOf(80), BigDecimal.valueOf(95), BigDecimal.valueOf(1500)};
            default -> new BigDecimal[] {BigDecimal.valueOf(15), BigDecimal.valueOf(22), BigDecimal.valueOf(80), BigDecimal.valueOf(95), BigDecimal.valueOf(1800)};
        };
    }

    public static boolean isOverLimit(String species, EnvironmentRecord record) {
        return !exceededMetrics(species, record).isEmpty();
    }

    /** 返回越限指标名列表，取值范围来自 {@link #METRIC_NAMES}。 */
    public static List<String> exceededMetrics(String species, EnvironmentRecord record) {
        BigDecimal[] r = range(species);
        List<String> metrics = new ArrayList<>(3);
        if (record.getTemperature().compareTo(r[0]) < 0 || record.getTemperature().compareTo(r[1]) > 0) {
            metrics.add("温度");
        }
        if (record.getHumidity().compareTo(r[2]) < 0 || record.getHumidity().compareTo(r[3]) > 0) {
            metrics.add("湿度");
        }
        if (record.getCo2Ppm() != null && record.getCo2Ppm() > r[4].intValue()) {
            metrics.add("CO₂");
        }
        return metrics;
    }

    /** 催蕾等场景使用的人类可读错误描述（含实测值与适宜区间）。 */
    public static List<String> describeErrors(String species, EnvironmentRecord record) {
        BigDecimal[] r = range(species);
        List<String> errors = new ArrayList<>();
        if (record.getTemperature().compareTo(r[0]) < 0 || record.getTemperature().compareTo(r[1]) > 0) {
            errors.add("温度 " + record.getTemperature() + "℃（适宜 " + r[0] + "~" + r[1] + "℃）");
        }
        if (record.getHumidity().compareTo(r[2]) < 0 || record.getHumidity().compareTo(r[3]) > 0) {
            errors.add("湿度 " + record.getHumidity() + "%（适宜 " + r[2] + "~" + r[3] + "%）");
        }
        if (record.getCo2Ppm() != null && record.getCo2Ppm() > r[4].intValue()) {
            errors.add("CO₂ " + record.getCo2Ppm() + "ppm（上限 " + r[4] + "ppm）");
        }
        return errors;
    }
}
