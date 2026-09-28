package com.wrapper.core;

/**
 * Правило маршрутизации
 */
public class RouteRule {
    private final String appPackage;
    private final String action;
    private final String target;

    public RouteRule(String appPackage, String action, String target) {
        this.appPackage = appPackage;
        this.action = action;
        this.target = target;
    }

    public String getAppPackage() {
        return appPackage;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public boolean isIntercept() {
        return "intercept".equals(action);
    }

    public boolean isRedirect() {
        return "redirect".equals(action);
    }

    @Override
    public String toString() {
        return "RouteRule{" + appPackage + " -> " + action + " -> " + target + "}";
    }
}
