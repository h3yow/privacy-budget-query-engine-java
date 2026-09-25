package com.github.h3yow.privacybudgetqueryenginejava.model;

/**
 * 单次请求声明的差分隐私参数。
 *
 * @param epsilon 隐私预算 epsilon，必须满足 0 &lt; epsilon &lt;= MAX_EPSILON
 * @param delta   高斯/松弛机制预留参数；本实现只允许 delta == 0（纯 DP）
 */
public record PrivacyParams(double epsilon, double delta) {

    /** 单次请求允许声明的 epsilon 上界，防止单笔请求耗尽或越界。 */
    public static final double MAX_EPSILON = 1.0;

    public PrivacyParams {
        // 仅做不可变字段装配；语义校验统一交给 PrivacyParamsValidator，
        // 保证所有入口（HTTP / Service / 测试）的拒绝原因一致。
    }
}
