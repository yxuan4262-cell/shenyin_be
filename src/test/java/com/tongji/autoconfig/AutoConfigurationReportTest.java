package com.tongji.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.ConditionAndOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.ConditionAndOutcomes;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

/**
 * 第三阶段练习 2：打印自动配置的"条件评估报告"。
 * <p>
 * 使用 {@link ApplicationContextRunner} + {@link Import} 白名单方式：
 * 只评估我们关心的自动配置类（Jackson、Redis），不激活全量自动配置，
 * 因此不会触发数据源、AI 模型等需要真实环境配置的组件。
 * <p>
 * 报告内容与启动时加 {@code debug: true} 看到的
 * CONDITIONS EVALUATION REPORT 完全同源。
 */
class AutoConfigurationReportTest {

    /**
     * 白名单：只导入要观察的两个自动配置类。
     */
    @Configuration
    @Import({JacksonAutoConfiguration.class, RedisAutoConfiguration.class})
    static class TargetedAutoConfig {
    }

    @Test
    void printJacksonAndRedisAutoConfigurationReport() {
        new ApplicationContextRunner()
                .withUserConfiguration(TargetedAutoConfig.class)
                .run(context -> printReport(context.getBeanFactory()));
    }

    private void printReport(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        ConditionEvaluationReport report = ConditionEvaluationReport.get(beanFactory);

        System.out.println();
        System.out.println("========== 自动配置条件评估报告（仅筛选 Jackson / Redis）==========");
        for (Map.Entry<String, ConditionAndOutcomes> entry :
                report.getConditionAndOutcomesBySource().entrySet()) {
            String source = entry.getKey();
            if (!source.contains("Jackson") && !source.contains("Redis")) {
                continue;
            }
            String verdict = entry.getValue().isFullMatch() ? "[生效]   " : "[未生效] ";
            System.out.println(verdict + source);
            for (ConditionAndOutcome conditionAndOutcome : entry.getValue()) {
                String message = conditionAndOutcome.getOutcome().getMessage();
                if (message != null) {
                    System.out.println("            - " + message);
                }
            }
        }
        System.out.println("==================================================================");
    }
}
