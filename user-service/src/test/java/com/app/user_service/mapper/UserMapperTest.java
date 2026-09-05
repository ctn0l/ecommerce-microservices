package com.app.user_service.mapper;

import com.app.user_service.dto.AddressDTO;
import com.app.user_service.dto.UserRequest;
import com.app.user_service.dto.UserResponse;
import com.app.user_service.model.Address;
import com.app.user_service.model.User;
import com.app.user_service.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userMapper = new UserMapper(new AddressMapper());
    }

    @Test
    void mapsRequestToEntityWithoutChangingServerManagedDefaults() {
        UserRequest request = requestWithAddress("Via Roma 1");

        User user = userMapper.toEntity(request);

        assertThat(user.getId()).isNull();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getFirstName()).isEqualTo("Mario");
        assertThat(user.getAddress().getStreet()).isEqualTo("Via Roma 1");
    }

    @Test
    void updatesExistingAddressInsteadOfReplacingIt() {
        User user = userMapper.toEntity(requestWithAddress("Via Roma 1"));
        Address existingAddress = user.getAddress();
        user.setRole(UserRole.ADMIN);

        userMapper.updateEntity(requestWithAddress("Via Milano 2"), user);

        assertThat(user.getAddress()).isSameAs(existingAddress);
        assertThat(user.getAddress().getStreet()).isEqualTo("Via Milano 2");
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void mapsEntityToResponse() {
        User user = userMapper.toEntity(requestWithAddress("Via Roma 1"));

        UserResponse response = userMapper.toResponse(user);

        assertThat(response.firstName()).isEqualTo("Mario");
        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(response.address().city()).isEqualTo("Roma");
    }

    @Test
    void handlesNullValuesAtMapperBoundary() {
        assertThat(userMapper.toEntity(null)).isNull();
        assertThat(userMapper.toResponse(null)).isNull();
        assertThat(new AddressMapper().toEntity(null)).isNull();
        assertThat(new AddressMapper().toDto(null)).isNull();
    }

    private UserRequest requestWithAddress(String street) {
        return new UserRequest(
                "Mario",
                "Rossi",
                "mario.rossi@example.com",
                "+39 333 1234567",
                new AddressDTO(street, "Roma", "RM", "Italia", "00100")
        );
    }
}
