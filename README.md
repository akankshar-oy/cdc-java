# CDC Pipeline (Java / Spring Boot)

A small backend project that demonstrates **Change Data Capture (CDC)** using Java, Spring Boot, MongoDB, Elasticsearch and Redis.

This is the Java/Spring Boot twin of an earlier Node.js CDC project. Same idea, different stack — built to show the same backend concept works the same way regardless of language.

## What is "Change Data Capture", in plain English?

Imagine you have a notebook (MongoDB) where you write down every order a customer places. Now imagine you also want a second, searchable copy of that notebook (Elasticsearch) that's always kept in sync — every time you write, cross out, or update something in the first notebook, someone should copy that change into the second notebook automatically, without you having to remember to do it twice.

That's Change Data Capture: instead of writing your data to two places by hand (which is error-prone and easy to forget), you write to **one** database, and a background process watches for every change and **replicates it** to other systems automatically.

Here, MongoDB has a built-in feature called **Change Streams** that lets an application "subscribe" to a live feed of every insert, update, and delete happening in a collection — a bit like subscribing to a notifications feed instead of having to constantly re-check the whole notebook. Our Spring Boot app listens to that feed and pushes every change into Elasticsearch, so we always have a fast, searchable copy of the data.

## Why do we need Elasticsearch and Redis if MongoDB already has the data?

- **Elasticsearch** is built for fast, flexible text search (like "find every order where the customer name contains 'ana'"). MongoDB *can* do this, but Elasticsearch is much better and faster at it. So we keep a read-optimized copy there, fed by the change stream.
- **Redis** (an in-memory key-value store) is used for two small but important jobs here:
  1. **Avoiding duplicate work.** If the app restarts or the change stream reconnects, MongoDB might redeliver an event we already processed. Before processing a change, we check a key in Redis ("have I already handled this exact change?"). If yes, we skip it.
  2. **Remembering where we left off.** Every change stream event comes with a "resume token" — think of it as a bookmark in the notebook. We save that bookmark to Redis after every change we process. If the app restarts, it reads the bookmark back from Redis and resumes the stream from exactly that point, instead of either missing changes or re-reading from the very beginning.

## Architecture

```
                 write path (REST)                     read path (REST)
   client  ───────────────────────────▶  MongoDB          client ────────▶  Elasticsearch
              POST/GET/PUT/DELETE       (orders coll.)                      GET /api/search
              /api/orders                    │
                                              │  MongoDB Change Stream
                                              │  (needs a replica set)
                                              ▼
                                   ChangeStreamRunner (Spring Boot)
                                              │
                             ┌────────────────┴────────────────┐
                             ▼                                  ▼
                          Redis                            Elasticsearch
                (dedup keys + resume token)         (upsert / delete "orders" index)
```

- **Write path**: `Order` REST controller reads/writes MongoDB directly through Spring Data MongoDB.
- **Capture**: `ChangeStreamRunner` opens a MongoDB change stream on the `orders` collection on startup.
- **Sync**: for every change event, it checks Redis for duplicates, then upserts/deletes the matching document in the Elasticsearch `orders` index, then saves the new resume token back to Redis.
- **Read path (search)**: `SearchController` queries Elasticsearch directly — this is the fast, always-in-sync search copy.

## Why does MongoDB need to run as a replica set?

Change Streams are built on top of MongoDB's internal **oplog** (operation log), which only exists when MongoDB runs as a replica set — even a single-node one. A standalone MongoDB instance has no oplog, so change streams simply aren't available on it. That's why `docker-compose.yml` starts Mongo with `--replSet rs0` and a one-shot `mongo-init` service that initializes the replica set.

## Tech stack

- Java 21, Spring Boot 3.3.13
- `spring-boot-starter-web` (plain blocking REST controllers, no reactive/WebFlux)
- `spring-boot-starter-data-mongodb` (blocking `MongoTemplate`/`MongoRepository`, not the reactive variant)
- `spring-boot-starter-data-elasticsearch`
- `spring-boot-starter-data-redis`
- `spring-boot-starter-actuator`
- MongoDB 7, Elasticsearch 8.15.0, Redis 7 (all via Docker)

No Lombok — every class has plain, explicit constructors/getters/setters so it reads like ordinary Java.

## How to run it

You need Docker Desktop running. Java 21 and Maven are only needed for the "run app locally" option.

### Option A — infra in Docker, app on your machine (recommended for development)

```bash
docker compose up -d mongo mongo-init elasticsearch redis
mvn spring-boot:run
```

This starts MongoDB, Elasticsearch and Redis in containers (with ports exposed to `localhost`), and runs the Spring Boot app directly on your machine, so you get fast restarts while developing.

### Option B — everything in Docker

```bash
docker compose up --build
```

This also builds and runs the Spring Boot app itself inside a container, using the other services' container names (`mongo`, `elasticsearch`, `redis`) to reach them.

### Shutting down

```bash
docker compose down
```

This stops and removes the containers. Data volumes (`mongo-data`, `es-data`) are kept, so your data survives the next `docker compose up`.

## REST API

### Orders (write path — talks to MongoDB directly)

Create an order (status defaults to `PLACED` if you don't send one):

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName": "Ananya Rao", "product": "Wireless Mouse", "quantity": 2, "price": 799.00}'
```

List all orders:

```bash
curl http://localhost:8080/api/orders
```

Get one order by id:

```bash
curl http://localhost:8080/api/orders/<id>
```

Update an order:

```bash
curl -X PUT http://localhost:8080/api/orders/<id> \
  -H "Content-Type: application/json" \
  -d '{"customerName": "Ananya Rao", "product": "Wireless Mouse", "quantity": 3, "price": 799.00, "status": "SHIPPED"}'
```

Delete an order:

```bash
curl -X DELETE http://localhost:8080/api/orders/<id>
```

### Search (read path — talks to Elasticsearch, the synced copy)

```bash
curl "http://localhost:8080/api/search?q=Ananya"
```

Search matches `customerName` or `product`, case-insensitively, as a "contains" match. After you create/update/delete an order, give the change stream a second or two to catch up before searching — it's asynchronous by design (that's the whole point of CDC: the write path doesn't wait around for the search index to update).

## Known limitations / things simplified on purpose (for a portfolio demo)

- Single-node MongoDB replica set and single-node Elasticsearch — fine for a demo, not how you'd run this in production.
- Elasticsearch security is disabled (`xpack.security.enabled=false`) purely to keep local setup simple.
- No authentication/authorization on the REST API itself — this project is about demonstrating CDC, not building a full auth layer.
- Dedup entries in Redis expire after 24 hours (a TTL, not permanent storage) — reasonable for a demo pipeline, would need a longer or different strategy for a real production system with rare restarts.
