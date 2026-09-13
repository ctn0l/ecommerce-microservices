package com.app.order_service.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class UserClient {
    private final RestClient client;

    public UserClient(@Qualifier("userRestClient") RestClient client) {
        this.client = client;
    }

    public void requireUser(Long userId) {
        try {
            UserIdentity user = client.get().uri("/api/users/{id}", userId).retrieve().body(UserIdentity.class);
            if (user == null || !userId.equals(user.id())) {
                throw new DependencyUnavailableException("User service", null);
            }
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
            }
            throw new DependencyUnavailableException("User service", exception);
        } catch (RestClientException exception) {
            throw new DependencyUnavailableException("User service", exception);
        }
    }

    public record UserIdentity(Long id) {}
}
