package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.PrivacyBudget;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 预算只读审计入口：按数据域查看总额、实耗、占用与可用。 */
@RestController
@RequestMapping("/api/budget")
public class BudgetController {

    private final Map<String, PrivacyBudget> budgets;

    public BudgetController(Map<String, PrivacyBudget> budgets) {
        this.budgets = budgets;
    }

    @GetMapping("/{domain}")
    public ResponseEntity<BudgetAudit> audit(@PathVariable String domain) {
        PrivacyBudget budget = budgets.get(domain);
        if (budget == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new BudgetAudit(
                domain, budget.total(), budget.consumed(), budget.reserved(), budget.available()));
    }
}
