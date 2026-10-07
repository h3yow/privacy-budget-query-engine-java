package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.privacy.BudgetAudit;
import com.github.h3yow.privacybudgetqueryenginejava.query.AggregationQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 只读预算审计入口：按数据域查看总额、已消耗、被占用与可用。 */
@RestController
@RequestMapping("/api/budgets")
public class BudgetAuditController {

    private final AggregationQueryService service;

    public BudgetAuditController(AggregationQueryService service) {
        this.service = service;
    }

    /** 所有数据域的预算审计视图。 */
    @GetMapping
    public List<BudgetAudit> auditAll() {
        return service.auditAll();
    }

    /** 单个数据域的预算审计视图；未知域返回 404。 */
    @GetMapping("/{domain}")
    public ResponseEntity<BudgetAudit> audit(@PathVariable String domain) {
        BudgetAudit audit = service.audit(domain);
        return audit == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(audit);
    }
}
