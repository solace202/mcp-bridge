-- The "existing system": a small shop database (customers, catalogue with stock, orders, refunds).
CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    email       VARCHAR(200) NOT NULL UNIQUE,
    country     VARCHAR(2)   NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id             BIGSERIAL PRIMARY KEY,
    sku            VARCHAR(32)  NOT NULL UNIQUE,
    name           VARCHAR(160) NOT NULL,
    category       VARCHAR(60)  NOT NULL,
    price_cents    BIGINT       NOT NULL CHECK (price_cents >= 0),
    stock          INT          NOT NULL CHECK (stock >= 0),
    reorder_level  INT          NOT NULL DEFAULT 10
);

CREATE TABLE orders (
    id                    BIGSERIAL PRIMARY KEY,
    customer_id           BIGINT      NOT NULL REFERENCES customers (id),
    status                VARCHAR(20) NOT NULL,   -- PAID, SHIPPED, DELIVERED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED
    total_cents           BIGINT      NOT NULL,
    refunded_cents        BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT refund_within_total CHECK (refunded_cents >= 0 AND refunded_cents <= total_cents)
);
CREATE INDEX idx_orders_customer ON orders (customer_id, created_at DESC);
CREATE INDEX idx_orders_created ON orders (created_at DESC);

CREATE TABLE order_items (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT NOT NULL REFERENCES orders (id),
    product_id        BIGINT NOT NULL REFERENCES products (id),
    quantity          INT    NOT NULL CHECK (quantity > 0),
    unit_price_cents  BIGINT NOT NULL
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE refunds (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT       NOT NULL REFERENCES orders (id),
    amount_cents  BIGINT       NOT NULL CHECK (amount_cents > 0),
    reason        VARCHAR(500) NOT NULL,
    created_by    VARCHAR(100) NOT NULL,
    reference     VARCHAR(64)  UNIQUE,   -- idempotency key supplied by the caller
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refunds_order ON refunds (order_id);

CREATE TABLE stock_movements (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT       NOT NULL REFERENCES products (id),
    delta       INT          NOT NULL,
    reason      VARCHAR(500) NOT NULL,
    created_by  VARCHAR(100) NOT NULL,
    reference   VARCHAR(64)  UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Bridge-owned tables -------------------------------------------------------------------------

-- Two-phase write actions: an MCP client prepares an action, a human approves, the client confirms with the token.
CREATE TABLE pending_actions (
    token         VARCHAR(64)  PRIMARY KEY,
    kind          VARCHAR(40)  NOT NULL,
    payload       JSONB        NOT NULL,
    summary       VARCHAR(500) NOT NULL,
    requested_by  VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING, CONFIRMED, CANCELLED, FAILED
    result        VARCHAR(1000),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    decided_at    TIMESTAMPTZ
);

-- Every MCP tool call, who made it, and how it ended.
CREATE TABLE tool_audit (
    id           BIGSERIAL PRIMARY KEY,
    api_key      VARCHAR(100) NOT NULL,
    tool         VARCHAR(80)  NOT NULL,
    arguments    JSONB        NOT NULL,
    outcome      VARCHAR(20)  NOT NULL,   -- OK, DENIED, ERROR
    detail       VARCHAR(500),
    duration_ms  INT          NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_audit_created ON tool_audit (created_at DESC);
