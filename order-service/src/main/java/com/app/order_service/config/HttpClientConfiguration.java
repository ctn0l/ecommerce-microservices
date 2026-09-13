package com.app.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpClientConfiguration {
    @Bean
    RestClient userRestClient(@Value("${services.user-url}") String url,
                              @Value("${services.connect-timeout}") Duration connect,
                              @Value("${services.read-timeout}") Duration read) {
        return create(url, connect, read);
    }

    @Bean
    RestClient productRestClient(@Value("${services.product-url}") String url,
                                 @Value("${services.connect-timeout}") Duration connect,
                                 @Value("${services.read-timeout}") Duration read) {
        return create(url, connect, read);
    }

    private RestClient create(String url, Duration connect, Duration read) {
        var http = HttpClient.newBuilder().connectTimeout(connect).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
