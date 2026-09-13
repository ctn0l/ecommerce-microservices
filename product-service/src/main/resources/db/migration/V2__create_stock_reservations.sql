CREATE TABLE stock_reservations (
    checkout_id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stock_reservation_items (
    checkout_id UUID NOT NULL REFERENCES stock_reservations(checkout_id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (checkout_id, product_id)
);
CREATE INDEX idx_stock_reservation_items_product ON stock_reservation_items(product_id);
