package com.app.user_service.mapper;

import com.app.user_service.dto.UserRequest;
import com.app.user_service.dto.UserResponse;
import com.app.user_service.model.Address;
import com.app.user_service.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserMapper {

    private final AddressMapper addressMapper;

    public User toEntity(UserRequest request) {
        if (request == null) {
            return null;
        }

        User user = new User();
        updateEntity(request, user);
        return user;
    }

    public UserResponse toResponse(User user) {
        if (user == null) {
            return null;
        }

        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                addressMapper.toDto(user.getAddress()),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    public void updateEntity(UserRequest request, User user) {
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        updateAddress(request, user);
    }

    private void updateAddress(UserRequest request, User user) {
        if (request.address() == null) {
            user.setAddress(null);
            return;
        }

        Address address = user.getAddress();
        if (address == null) {
            user.setAddress(addressMapper.toEntity(request.address()));
            return;
        }

        addressMapper.updateEntity(request.address(), address);
    }
}
