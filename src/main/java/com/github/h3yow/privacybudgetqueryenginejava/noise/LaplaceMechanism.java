package com.github.h3yow.privacybudgetqueryenginejava.noise;

import java.util.Random;

/**
 * 基于种子的拉普拉斯扰动机制。
 *
 * <p>可复现性：噪声仅由构造时传入的种子决定，
 * 相同种子 + 相同 scale 必然产生相同噪声序列；
 * 不同种子在统计上产生独立噪声。
 */
public class LaplaceMechanism {

    private final Random random;

    public LaplaceMechanism(long seed) {
        this.random = new Random(seed);
    }

    /**
     * 采样一个均值为 0 的拉普拉斯噪声。
     *
     * @param scale 拉普拉斯尺度参数 b = 灵敏度 / epsilon
     */
    public double sample(double scale) {
        if (scale <= 0 || Double.isNaN(scale) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("scale 必须为正有限值: " + scale);
        }
        // 逆变换采样：Laplace(0, b) = -b * sign(u) * ln(1 - 2|u|), u ~ U(-0.5, 0.5)
        double u = random.nextDouble() - 0.5;
        return -scale * Math.signum(u) * Math.log1p(-2.0 * Math.abs(u));
    }
}
