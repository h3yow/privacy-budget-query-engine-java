package com.github.h3yow.privacybudgetqueryenginejava.dp;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 拉普拉斯机制：Lap(scale = sensitivity / epsilon)。
 *
 * <p>可复现性约定：给定 (sensitivity, epsilon, seed)，噪声由 {@code new Random(seed)}
 * 产生的第一个均匀随机数经逆 CDF 变换得到。同一 JVM 上相同输入必然得到相同输出；
 * 不同种子会得到不同的均匀样本，从而产生合理差异。
 */
@Component
public class LaplaceMechanism {

    /**
     * 生成单个拉普拉斯噪声样本。
     *
     * @param sensitivity L1 灵敏度（&gt; 0）
     * @param epsilon     本次分配到的 epsilon（&gt; 0）
     * @param seed        确定性种子
     * @return 噪声值
     */
    public double noise(double sensitivity, double epsilon, long seed) {
        double b = scale(sensitivity, epsilon);
        Random random = new Random(seed);
        // u ∈ [-0.5, 0.5)
        double u = random.nextDouble() - 0.5;
        double abs = Math.abs(u);
        // nextDouble()==0 时 u 恰为 -0.5，此时 1-2|u|=0 会得到 ±∞，做一次防御性收缩。
        if (abs >= 0.5) {
            abs = Math.nextDown(0.5);
        }
        double sign = u >= 0 ? 1.0 : -1.0;
        // 逆 CDF：X = -b * sign(u) * ln(1 - 2|u|)
        return -sign * b * Math.log(1.0 - 2.0 * abs);
    }

    /**
     * 拉普拉斯尺度参数 b = sensitivity / epsilon。
     */
    public double scale(double sensitivity, double epsilon) {
        return sensitivity / epsilon;
    }
}
