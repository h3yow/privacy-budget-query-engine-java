package com.github.h3yow.privacybudgetqueryenginejava.api;

import com.github.h3yow.privacybudgetqueryenginejava.query.AggregationQueryService;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryRequest;
import com.github.h3yow.privacybudgetqueryenginejava.query.QueryResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST 入口：接收统计请求，返回扰动后的聚合结果或可区分的拒绝原因。 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final AggregationQueryService service;

    public QueryController(AggregationQueryService service) {
        this.service = service;
    }

    @PostMapping
    public QueryResult query(@RequestBody QueryRequest request) {
        return service.query(request);
    }
}
