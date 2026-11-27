# fix-me

A simulated electronic trading platform in pure Java. It has three independent
components that talk to each other over TCP using a simplified version of the
[FIX protocol](https://en.wikipedia.org/wiki/Financial_Information_eXchange):

| Component | Role |
|---|---|
| **Router** | The central message dispatcher, or "market connectivity provider". Brokers and markets connect to it. It validates each message and forwards it, with no business logic. |
| **Broker** | Sends **Buy** and **Sell** orders, typed on stdin, and receives **Executed** / **Rejected** reports. |
| **Market** | Holds a book of instruments and executes or rejects the orders it receives. |

Key requirements of the subject and where they are met:

| Requirement | Implementation |
|---|---|
| Non-blocking sockets | `java.nio` `Selector` + `SocketChannel` / `ServerSocketChannel` in every component |
| Java executor framework for message handling | `ExecutorService` thread pools in the Router (routing chain) and in the Market (order execution) |
| Multi-module Maven build | Parent `pom.xml` with 4 modules: `common`, `router`, `broker`, `market` |
| Chain of responsibility | Router pipeline: `ChecksumHandler` → `DestinationHandler` → `ForwardHandler` |

---

## Build & run

Requirements: JDK 25+ and Maven 3.9+.

```bash
cd fixme
mvn clean package
```

This builds `common` first, then produces three self-contained runnable jars:

```
router/target/router.jar
broker/target/broker.jar
market/target/market.jar
```

Run each component in its own terminal, from `fixme/`:

```bash
java -jar router/target/router.jar                    # 1. start first
java -jar market/target/market.jar market/config.txt  # 2. prints its id, e.g. 100000
java -jar broker/target/broker.jar                    # 3. prints its id, e.g. 100001
```

The market's config path argument is optional. It defaults to `config.txt` in
the current directory. If no instrument can be loaded, the market exits with an error.

### Broker commands

```
buy  <SYMBOL> <QUANTITY> <PRICE> <MARKET_ID>     e.g.  buy AAPL 10 192 100000
sell <SYMBOL> <QUANTITY> <PRICE> <MARKET_ID>     e.g.  sell AAPL 5 192 100000
quit
```

`MARKET_ID` is the id the market printed when it connected. Invalid input
(wrong number of arguments, unknown side, non-numeric or non-positive numbers,
a symbol containing `|` or `=`) prints the usage text and is never sent.

### Example session

```
[Broker] sent order 1: buy 10 AAPL @ 192 to market 100000
[Broker] EXECUTED order 1: AAPL (market 100000)
[Broker] sent order 2: buy 9999 TSLA @ 255 to market 100000
[Broker] REJECTED order 2: TSLA (market 100000) -> not enough quantity (available 700)
[Broker] sent order 3: buy 1 AAPL @ 192 to market 999999
[Broker] REJECTED order 3: AAPL (market 000000) -> unknown destination 999999
```

---

## Project structure

```
fixme/
├── pom.xml                      parent: modules, Java version, plugin versions
├── common/                      shared library (no main)
│   ├── FIXMessage.java          build / parse / checksum / length-prefix framing
│   ├── MessageReader.java       reassembles framed messages from a non-blocking channel
│   ├── Client.java              abstract NIO client used by Broker and Market
│   └── Utils.java               host and port constants
├── router/
│   ├── Router.java              selector loop, accept/read/write, id assignment, routing table
│   ├── Connection.java          one connected broker or market
│   ├── RoutedMessage.java       one message travelling through the chain
│   ├── Handler.java             chain-of-responsibility base class
│   ├── ChecksumHandler.java     step 1: validate tag 10
│   ├── DestinationHandler.java  step 2: find tag 56 in the routing table
│   └── ForwardHandler.java      step 3: queue the message for the destination
├── broker/
│   └── Broker.java              stdin commands → orders, prints reports
└── market/
    ├── Market.java              order execution rules, replies
    ├── Instrument.java          one tradable instrument (thread-safe stock)
    └── config.txt               the instrument list
```

---

## The protocol

### Message format

```
49=100001|35=D|56=100000|11=1|55=AAPL|54=1|38=10|44=192|10=022|
```

* Each field is a `tag=value` pair, and fields are separated by `|`.
* **Every message starts with the sender's router-assigned id (tag 49)** and **ends with the checksum (tag 10)**, as the subject requires.

**Choice: `|` instead of SOH (`\u0001`).** Real FIX separates fields with the
non-printable SOH character. `|` keeps logs and test traffic readable, and
it is a common convention when FIX is printed. Because of this, values may not
contain `|` or `=`, and the broker refuses such input.

### Tags used

| Tag | Name | Order `35=D` (broker → market) | Report `35=8` (market → broker) |
|---|---|---|---|
| `49` | SenderCompID | broker id | market id (`000000` if the router rejects) |
| `56` | TargetCompID | market id | broker id |
| `35` | MsgType | `D` NewOrderSingle | `8` ExecutionReport |
| `11` | ClOrdID | order number | same number, echoed back |
| `55` | Symbol | instrument | instrument |
| `54` | Side | `1` buy / `2` sell | (not used) |
| `38` | OrderQty | quantity | (not used) |
| `44` | Price | price | (not used) |
| `39` | OrdStatus | (not used) | `2` Executed / `8` Rejected |
| `58` | Text | (not used) | reason, only when rejected |
| `10` | CheckSum | always last | always last |

The router also sends a **Logon** (`35=A|56=<your id>|10=...|`) right after a client connects.

Tags `49`/`56` and `35=D`/`35=8` follow the real FIX numbering, so the
messages look familiar to anyone who knows FIX. Only the subset the subject
needs is used. Instrument, Quantity, Market and Price are the mandatory Buy/Sell fields.

### Checksum (tag 10)

This is the real FIX algorithm: the **sum of every byte before `10=`
(separators included), modulo 256, written as 3 digits**. The router recomputes
it on every message and drops the message if it doesn't match, so a corrupted or
tampered message is never delivered.

All text is encoded as **US-ASCII**, so that one character is always one byte and the
checksum and the length prefix are computed on the same bytes on every side.

### Framing: a 4-byte length prefix

On the wire, each message is sent as `[4-byte big-endian int: body length][body bytes]`.

**Why:** TCP is a byte stream, not a message stream. One `read()` can return
half a message, or two messages glued together. Real FIX solves this with tags
`8` (BeginString) and `9` (BodyLength). A binary length prefix does the same
job more simply. `MessageReader` keeps partial state per connection and returns a
message only once it is complete.

An impossible length (≤ 0 or > 64 KiB) means the stream can no longer be
parsed. The reader throws, and only that connection is closed.

### Unique ids

The router hands out **6-digit ids from a single `AtomicInteger` starting
at 100000**, in connection order, shared by both ports. Ids are unique across
brokers and markets, which makes one routing table enough. They depend on
connection order, so a broker can't know a market's id in advance. That is why
the market prints its id and the broker takes it as a command argument (the
"Market" field).

---

## Architecture & design choices

### Threading model

One rule applies everywhere: **only the selector thread touches selection keys
and sockets.** Other threads communicate with it through concurrent queues plus
`selector.wakeup()`.

```
            Router process
 ┌──────────────────────────────────────────────────────────────┐
 │ selector thread                       worker pool (4 threads) │
 │  accept  → assign id, send Logon                               │
 │  read    → MessageReader → pool.submit(chain.handle(msg)) ───▶ │ Checksum → Destination → Forward
 │                                                                 │                              │
 │  drain needsWrite → enable OP_WRITE ◀── needsWrite.add(dest) ◀─┤ dest.outbound.add(bytes)  ◀──┘
 │  write   → flush dest.outbound                    + wakeup()   │
 └──────────────────────────────────────────────────────────────┘
```

* **Why a single selector thread plus a pool:** non-blocking I/O
  multiplexes all connections on one thread, with no thread per client. The CPU work
  (parsing, validating, routing, executing orders) runs on the pool, so slow
  processing never stalls the network loop.
* **Why workers don't write to sockets themselves:** `SelectionKey` and the
  channels aren't meant to be manipulated concurrently with a running `select()`.
  Workers append to the destination's `ConcurrentLinkedQueue`, call
  `requestWrite(dest)`, and the selector thread enables `OP_WRITE` and does the
  actual write.
* **Write loop:** each buffer is `peek`ed, written, and only `poll`ed once fully
  sent. If the kernel buffer is full, the loop stops and waits for the next
  `OP_WRITE`. When the queue is empty, interest drops back to `OP_READ`, which
  avoids a busy loop.
* **Failure isolation:** every key is handled inside its own `try/catch`. A
  client that disconnects or sends garbage is removed from the routing table and
  closed, and every other connection keeps running.

### Router: chain of responsibility

Each `Handler` checks one thing, then either passes the message on with
`super.handle(m)` or stops it by returning:

1. **`ChecksumHandler`** recomputes tag 10. If it's invalid, the message is dropped and logged. Nothing in a
   corrupted message can be trusted (not even its order id), so no reply is sent.
2. **`DestinationHandler`** looks up tag 56 in the routing table. It rejects:
   * a non-numeric or missing destination,
   * an unknown destination (for example, a market that disconnected),
   * broker → broker and market → market (brokers only talk to markets and vice versa).

   On rejection, the **sender receives an ExecutionReport `39=8`** from
   `49=000000` (a reserved id the router never assigns), with the reason in `58`.
   The broker always gets an answer instead of silence.
3. **`ForwardHandler`** queues the **raw message unchanged** on the destination.

Why this design:

* Each check is isolated, easy to test, and a new one (for example, rate limiting)
  is one new class plus one `setNext`.
* **Handlers are stateless.** One chain is shared by all pool threads, and all
  per-message data travels in `RoutedMessage`. That's what makes the chain safe
  to run concurrently.
* The router does **no business logic**. It never looks at symbols or
  quantities, and the same code routes orders and reports in both directions.
  Sending back "cannot deliver" is a transport-level error, much like a FIX session-level reject.
* `ForwardHandler` receives a callback (`Consumer<Connection>`) instead of the
  selector, so it knows nothing about NIO. This also solves the ordering problem
  of the chain being built before the selector is opened.

### `common.Client`: template method

`Broker` and `Market` share all their networking through the abstract
`Client`. It handles the non-blocking connect, the selector loop, framing, the write
queue and the Logon. The subclasses only implement two hooks:

```java
protected abstract void onIdAssigned(int id);
protected abstract void onMessage(Map<String, String> fields);
```

* The Logon (`35=A`) is handled in the base class, because it's protocol and not business logic.
* `awaitId()` blocks on a `CountDownLatch` until the id is received, so a client
  never sends a message without its id.
* `send()` is thread-safe: it queues the message and calls `wakeup()`. The interest change is applied by the IO thread.
* If the router is unreachable or disconnects, the client prints a message and exits instead of hanging.

### Market: execution rules

`onMessage` runs on the IO thread, so it only submits the order to a thread
pool. A worker validates the order in this sequence, stopping at the first failure:

| # | Check | Reject reason |
|---|---|---|
| 1 | tag 49 present | (dropped: there is nobody to reply to) |
| 2 | tags 55, 54, 38, 44 present | `missing field (55, 54, 38 or 44)` |
| 3 | quantity and price are integers | `quantity and price must be integers` |
| 4 | quantity and price > 0 | `quantity and price must be positive` |
| 5 | instrument is listed on this market | `instrument not listed on this market` |
| 6 | instrument is traded | `instrument not traded` |
| 7 | price equals the instrument's price | `Price provided is not adequate.` |
| 8 | side is `1` or `2` | `invalid side (54 must be 1 or 2)` |
| 9 | buy: enough quantity available | `not enough quantity (available N)` |

Rules 5, 6 and 9 are the ones the subject requires. Rule 7 is a deliberate
simplification: the market trades only at its listed price. Buy and Sell are
seen **from the market's side**: a broker buying **decreases** the market's
stock, and a broker selling **increases** it.

`Instrument.buy`, `sell` and `getQuantity` are `synchronized`, so two workers
executing orders on the same instrument can't interleave the check and the update.

The reply swaps tags 49 and 56 and echoes tag 11, so the broker can match the
report to its order.

### Maven

* The **parent pom** (`packaging=pom`) declares the modules. It also pins the Java version,
  plugin versions, and the version of `common` (`dependencyManagement`), so all
  modules stay consistent.
* **`common`** is a plain library. `router`, `broker` and `market` depend on it and
  never on each other, which keeps each component independent.
* **`maven-shade-plugin`** packages each component together with `common` into one
  runnable jar with its `Main-Class`. A plain jar would fail with
  `ClassNotFoundException` for the `common` classes.

---

## Testing scenarios

Every scenario below has been verified end to end:

| Scenario | Expected |
|---|---|
| Buy at the listed price with enough stock | Executed, the market's stock decreases |
| Sell at the listed price | Executed, the market's stock increases |
| Buy more than available | Rejected: not enough quantity |
| Unknown symbol / non-traded instrument | Rejected |
| Wrong price | Rejected |
| Order to a non-existent market id | Rejected by the router: unknown destination |
| Broker → broker, market → market | Rejected by the router |
| Tampered message (checksum mismatch) | Dropped by the router |
| Garbage frame length | Only that client is disconnected |
| Two brokers at the same time | Each receives only its own reports |
| Kill a broker or the market | The router keeps running and removes it from the table |
| Start the router twice | `fatal: Address already in use` |
| Start a client without a router | Clear error, exits immediately |

---

## Limitations / not implemented

* **Bonus** (transaction database, fail-over / transaction recovery) is not implemented.
* Ids are not persistent: a component that reconnects gets a new id.
* Prices and quantities are integers.
