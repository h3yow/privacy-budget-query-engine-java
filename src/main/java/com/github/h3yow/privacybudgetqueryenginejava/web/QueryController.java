package com.github.h3yow.privacybudgetqueryenginejava.web;

import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationRequest;
import com.github.h3yow.privacybudgetqueryenginejava.model.AggregationResponse;
import com.github.h3yow.privacybudgetqueryenginejava.model.BudgetSnapshot;
import com.github.h3yow.privacybudgetqueryenginejava.service.PrivacyQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 差分隐私聚合查询 HTTP 入口。
 *
 * <ul>
 *   <li>POST /api/query：提交一次聚合请求；成功只返回扰动结果，失败返回原因码（422）。</li>
 *   <li>GET  /api/budget/{domain}：查看数据域预算快照（已消耗/剩余）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final PrivacyQueryService service;

    public QueryController(PrivacyQueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public AggregationResponse query(@RequestBody AggregationRequest request) {
        return service.query(request);
    }

    @GetMapping("/budget/{domain}")
    public BudgetSnapshot budget(@PathVariable String domain) {
        return service.budget(domain);
    }
}
