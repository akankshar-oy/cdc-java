package com.cdc.pipeline.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final OrderSearchRepository orderSearchRepository;

    public SearchController(OrderSearchRepository orderSearchRepository) {
        this.orderSearchRepository = orderSearchRepository;
    }

    @GetMapping
    public List<OrderSearchDocument> search(@RequestParam("q") String query) {
        return orderSearchRepository.findByCustomerNameContainingIgnoreCaseOrProductContainingIgnoreCase(query, query);
    }
}
