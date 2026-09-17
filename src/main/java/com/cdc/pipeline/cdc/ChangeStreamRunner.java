package com.cdc.pipeline.cdc;

import com.cdc.pipeline.order.Order;
import com.cdc.pipeline.search.OrderSearchDocument;
import com.cdc.pipeline.search.OrderSearchRepository;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.OperationType;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.DefaultMessageListenerContainer;
import org.springframework.data.mongodb.core.messaging.Message;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * On startup, opens a MongoDB change stream on the "orders" collection and mirrors every
 * insert/update/replace/delete into Elasticsearch. Requires Mongo to run as a replica set
 * (even a single-node one) because change streams are built on the oplog, which only exists
 * for replica sets.
 */
@Component
public class ChangeStreamRunner implements MessageListener<ChangeStreamDocument<Document>, Order> {

    private static final Logger log = LoggerFactory.getLogger(ChangeStreamRunner.class);
    private static final String RESUME_TOKEN_KEY = "cdc:resumeToken";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final MongoTemplate mongoTemplate;
    private final OrderSearchRepository orderSearchRepository;
    private final StringRedisTemplate redisTemplate;

    public ChangeStreamRunner(MongoTemplate mongoTemplate, OrderSearchRepository orderSearchRepository,
                               StringRedisTemplate redisTemplate) {
        this.mongoTemplate = mongoTemplate;
        this.orderSearchRepository = orderSearchRepository;
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startChangeStream() {
        MessageListenerContainer container = new DefaultMessageListenerContainer(mongoTemplate);

        ChangeStreamRequest.ChangeStreamRequestBuilder<Order> requestBuilder = ChangeStreamRequest
                .builder(this)
                .collection("orders")
                .fullDocumentLookup(FullDocument.UPDATE_LOOKUP);

        String savedToken = redisTemplate.opsForValue().get(RESUME_TOKEN_KEY);
        if (savedToken != null) {
            requestBuilder.resumeAfter(BsonDocument.parse(savedToken));
            log.info("Found a saved resume token in Redis, resuming change stream from there");
        } else {
            log.info("No saved resume token found, starting change stream from now");
        }

        container.register(requestBuilder.build(), Order.class);
        container.start();

        log.info("Change stream listener started on collection 'orders'");
    }

    @Override
    public void onMessage(Message<ChangeStreamDocument<Document>, Order> message) {
        ChangeStreamDocument<Document> raw = message.getRaw();
        OperationType operationType = raw.getOperationType();
        BsonDocument resumeToken = raw.getResumeToken();

        String documentId = operationType == OperationType.DELETE
                ? extractId(raw.getDocumentKey())
                : message.getBody().getId();

        String dedupKey = "cdc:dedup:" + documentId + ":" + resumeToken.toJson();

        if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
            log.info("Skipped duplicate change event for order {}", documentId);
            return;
        }

        switch (operationType) {
            case INSERT, UPDATE, REPLACE -> {
                orderSearchRepository.save(toSearchDocument(message.getBody()));
                log.info("Synced order {} to Elasticsearch ({})", documentId, operationType);
            }
            case DELETE -> {
                orderSearchRepository.deleteById(documentId);
                log.info("Removed order {} from Elasticsearch", documentId);
            }
            default -> log.info("Ignoring change event of type {} for order {}", operationType, documentId);
        }

        // Marked only after the Elasticsearch write succeeds: a crash in between re-delivers the
        // event on resume instead of skipping it as "already seen", which is what makes this
        // at-least-once rather than at-most-once.
        redisTemplate.opsForValue().set(dedupKey, "1", DEDUP_TTL);
        redisTemplate.opsForValue().set(RESUME_TOKEN_KEY, resumeToken.toJson());
    }

    private String extractId(BsonDocument documentKey) {
        BsonValue idValue = documentKey.get("_id");
        return idValue.isObjectId() ? idValue.asObjectId().getValue().toHexString() : idValue.asString().getValue();
    }

    private OrderSearchDocument toSearchDocument(Order order) {
        long createdAt = toEpochMilli(order.getCreatedAt());
        long updatedAt = toEpochMilli(order.getUpdatedAt());
        return new OrderSearchDocument(order.getId(), order.getCustomerName(), order.getProduct(),
                order.getQuantity(), order.getPrice(), order.getStatus(), createdAt, updatedAt);
    }

    private long toEpochMilli(Instant instant) {
        return instant != null ? instant.toEpochMilli() : 0L;
    }
}
