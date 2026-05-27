package com.shortlink.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.shortlink.interceptor.AccessLogInterceptor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Web MVC configuration: registers interceptors and Sentinel flow rules.
 * <p>
 * Sentinel rate limiting is applied via @SentinelResource annotations on service methods.
 * Flow rules are loaded programmatically at startup.
 * Note: Sentinel 1.8.7 WebMVC adapter uses javax.servlet (incompatible with Spring Boot 3.x jakarta.servlet),
 * so we use Sentinel's annotation-based approach instead of the WebMVC interceptor.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AccessLogInterceptor accessLogInterceptor;

    @Value("${short-link.sentinel.create-qps:1000}")
    private double createQps;

    @Value("${short-link.sentinel.redirect-qps:5000}")
    private double redirectQps;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessLogInterceptor).addPathPatterns("/**");
    }

    /**
     * Initializes Sentinel flow rules for all protected resources.
     */
    @PostConstruct
    public void initSentinelRules() {
        List<FlowRule> rules = new ArrayList<>();

        // Rule for create short URL
        FlowRule createRule = new FlowRule();
        createRule.setResource("createShortUrl");
        createRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        createRule.setCount(createQps);
        createRule.setStrategy(RuleConstant.STRATEGY_DIRECT);
        createRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        rules.add(createRule);

        // Rule for resolve/redirect
        FlowRule redirectRule = new FlowRule();
        redirectRule.setResource("resolveShortUrl");
        redirectRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        redirectRule.setCount(redirectQps);
        redirectRule.setStrategy(RuleConstant.STRATEGY_DIRECT);
        redirectRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        rules.add(redirectRule);

        FlowRuleManager.loadRules(rules);
    }

    /**
     * Register the Sentinel @SentinelResource aspect for AOP-based rate limiting.
     */
    @Bean
    public com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect sentinelResourceAspect() {
        return new com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect();
    }
}
