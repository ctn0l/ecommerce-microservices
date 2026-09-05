package com.app.user_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressDTO(
        @NotBlank
        @Size(max = 150)
        String street,

        @NotBlank
        @Size(max = 100)
        String city,

        @Size(max = 100)
        String state,

        @NotBlank
        @Size(max = 100)
        String country,

        @NotBlank
        @Size(max = 20)
        String zipcode
) {
}
