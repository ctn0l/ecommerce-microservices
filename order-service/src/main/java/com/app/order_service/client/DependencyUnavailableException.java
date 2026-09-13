package com.app.order_service.client;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DependencyUnavailableException extends ResponseStatusException {
    public DependencyUnavailableException(String service, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, service + " is temporarily unavailable", cause);
    }
}
