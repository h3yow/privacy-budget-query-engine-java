package com.github.h3yow.privacybudgetqueryenginejava.budget;

import com.github.h3yow.privacybudgetqueryenginejava.exception.PrivacyQueryException;
import com.github.h3yow.privacybudgetqueryenginejava.exception.RejectionReason;
import com.github.h3yow.privacybudgetqueryenginejava.model.BudgetSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据域 -> {@link BudgetAccount} 的注册表与三阶段操作入口。
 *
 * <p>每个数据域一个独立账户；账户创建由 {@link ConcurrentHashMap#computeIfAbsent}
 * 保证幂等。真正的原子性在账户自身的 synchronized 临界区内完成，
 * 因此同一数据域的并发预占不会超支，不同数据域互不阻塞。
 */
@Component
public class BudgetManager {

    private final double limit;
    private final ConcurrentHashMap<String, BudgetAccount> accounts = new ConcurrentHashMap<>();

    public BudgetManager(@Value("${dp.budget.limit-per-domain:3.0}") double limit) {
        this.limit = limit;
    }

    public double getLimit() {
        return limit;
    }

    private BudgetAccount account(String domain) {
        return accounts.computeIfAbsent(domain, d -> new BudgetAccount(limit));
    }

    /**
     * 原子预占；余额不足时以 {@link RejectionReason#BUDGET_EXHAUSTED} 拒绝，
     * 且账户状态保持不变（不存在部分扣减）。
     */
    public void reserve(String domain, double epsilon) {
        BudgetAccount account = account(domain);
        boolean ok = account.reserve(epsilon);
        if (!ok) {
            throw new PrivacyQueryException(RejectionReason.BUDGET_EXHAUSTED,
                    String.format("数据域 [%s] 预算不足：剩余 %.6f（含预占），本次申请 %.6f，上界 %.6f",
                            domain, account.getRemaining(), epsilon, account.getLimit()));
        }
    }

    /**
     * 提交：预占额转为永久消耗。
     */
    public void commit(String domain, double epsilon) {
        account(domain).commit(epsilon);
    }

    /**
     * 回滚：预占额原样归还。
     */
    public void rollback(String domain, double epsilon) {
        account(domain).rollback(epsilon);
    }

    public BudgetSnapshot snapshot(String domain) {
        BudgetAccount account = account(domain);
        return new BudgetSnapshot(domain, account.getSpent(), account.getLimit(),
                account.getRemaining());
    }
}
