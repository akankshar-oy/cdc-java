# CDC Pipeline (Java / Spring Boot)

A Change Data Capture pipeline that keeps an Elasticsearch search index continuously in sync with MongoDB, without dual writes.

The application writes orders to MongoDB only. A background listener subscribes to MongoDB Change Streams and replicates every insert, update and delete into Elasticsearch. Redis stores the change stream resume token for crash recovery and dedup keys for idempotent processing.

## Architecture

```
                    ┌─────────────── Spring Boot application ──────────────┐
                    │                                                      │
 POST/PUT/DELETE/   │  OrderController ────────── read / write ────────────┼──▶ MongoDB
 GET /api/orders ──▶│                                                      │    (orders collection,
                    │                                                      │     single-node replica set)
                    │  ChangeStreamRunner ◀───────── change stream ────────┼────
                    │           │                                          │
                    │           ├────────── upsert / delete ───────────────┼──▶ Elasticsearch
                    │           │                                          │    (orders index)
                    │           └──── resume token + dedup keys ◀─────────▶┼──▶ Redis
                    │                                                      │
 GET /api/search ──▶│  SearchController ─────────── query ─────────────────┼──▶ Elasticsearch
                    │                                                      │
                    └──────────────────────────────────────────────────────┘
```

- **Write path** — `OrderController` reads and writes MongoDB through Spring Data MongoDB.
- **Capture** — `ChangeStreamRunner` opens a change stream on the `orders` collection at startup, resuming from the token stored in Redis.
- **Sync** — each event is checked against Redis for redelivery, then upserted into or deleted from the Elasticsearch `orders` index. The resume token and dedup key are written only after the Elasticsearch write succeeds, which keeps delivery at-least-once.
- **Read path** — `SearchController` queries Elasticsearch directly.

Change streams are built on MongoDB's oplog, which only exists on a replica set. MongoDB therefore runs as a single-node replica set (`--replSet rs0`), initialized by the `mongo-init` service in `docker-compose.yml`.

## Stack

Java 21 · Spring Boot 3.3 · MongoDB 7 · Elasticsearch 8.15 · Redis 7 · Docker Compose

Blocking Spring MVC and Spring Data (no WebFlux), with `spring-boot-starter-data-mongodb`, `-data-elasticsearch`, `-data-redis` and `-actuator`.

## Running it

Requires Docker. Java 21 and Maven are needed only for option A.

**A — infrastructure in Docker, application locally:**

```bash
docker compose up -d mongo mongo-init elasticsearch redis
mvn spring-boot:run
```

**B — everything in Docker:**

```bash
docker compose up --build
```

Shut down with `docker compose down`. Volumes persist between runs.

## API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/orders` | Create an order (`status` defaults to `PLACED`) |
| `GET` | `/api/orders` | List all orders |
| `GET` | `/api/orders/{id}` | Fetch one order |
| `PUT` | `/api/orders/{id}` | Update an order |
| `DELETE` | `/api/orders/{id}` | Delete an order |
| `GET` | `/api/search?q=` | Search the Elasticsearch index by customer name or product |

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName": "Ananya Rao", "product": "Wireless Mouse", "quantity": 2, "price": 799.00}'

curl "http://localhost:8080/api/search?q=Ananya"
```

Propagation to the search index is asynchronous — the write path does not block on indexing.
