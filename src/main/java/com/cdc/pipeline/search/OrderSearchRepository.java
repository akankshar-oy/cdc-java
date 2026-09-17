package com.cdc.pipeline.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface OrderSearchRepository extends ElasticsearchRepository<OrderSearchDocument, String> {

    List<OrderSearchDocument> findByCustomerNameContainingIgnoreCaseOrProductContainingIgnoreCase(
            String customerName, String product);
}
