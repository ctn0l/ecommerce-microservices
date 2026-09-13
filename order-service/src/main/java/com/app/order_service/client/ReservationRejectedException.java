package com.app.order_service.client;

public class ReservationRejectedException extends RuntimeException {
    public ReservationRejectedException() {
        super("Product is missing, inactive, or has insufficient stock");
    }
}
