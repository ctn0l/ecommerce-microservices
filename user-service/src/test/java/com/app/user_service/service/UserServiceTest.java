package com.app.user_service.service;

import com.app.user_service.dto.AddressDTO;
import com.app.user_service.dto.UserRequest;
import com.app.user_service.dto.UserResponse;
import com.app.user_service.mapper.AddressMapper;
import com.app.user_service.mapper.UserMapper;
import com.app.user_service.model.User;
import com.app.user_service.model.enums.UserRole;
import com.app.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        UserMapper userMapper = new UserMapper(new AddressMapper());
        userService = new UserService(userRepository, userMapper);
    }

    @Test
    void createsUserFromRequestAndReturnsResponse() {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.addUser(request("  MARIO.ROSSI@EXAMPLE.COM  "));

        assertThat(response.email()).isEqualTo("mario.rossi@example.com");
        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(response.address().city()).isEqualTo("Roma");
        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    void returnsMappedUsers() {
        User user = new UserMapper(new AddressMapper()).toEntity(request("mario@example.com"));
        when(userRepository.findAll()).thenReturn(List.of(user));

        List<UserResponse> responses = userService.fetchAllUsers();

        assertThat(responses)
                .singleElement()
                .extracting(UserResponse::email)
                .isEqualTo("mario@example.com");
    }

    @Test
    void returnsUserWhenItExists() {
        User user = new UserMapper(new AddressMapper()).toEntity(request("mario@example.com"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        Optional<UserResponse> response = userService.fetchUser(1L);

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().email()).isEqualTo("mario@example.com");
    }

    @Test
    void updatesAllowedFieldsWithoutChangingRole() {
        User existingUser = new UserMapper(new AddressMapper()).toEntity(request("old@example.com"));
        existingUser.setRole(UserRole.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.saveAndFlush(existingUser)).thenReturn(existingUser);

        Optional<UserResponse> response = userService.updateUser(1L, request("NEW@EXAMPLE.COM"));

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().email()).isEqualTo("new@example.com");
        assertThat(response.orElseThrow().role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void returnsEmptyWhenUserToUpdateDoesNotExist() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<UserResponse> response = userService.updateUser(99L, request("new@example.com"));

        assertThat(response).isEmpty();
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void deletesExistingUser() {
        User user = new UserMapper(new AddressMapper()).toEntity(request("mario@example.com"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        boolean deleted = userService.deleteUser(1L);

        assertThat(deleted).isTrue();
        verify(userRepository).delete(user);
    }

    @Test
    void doesNotDeleteMissingUser() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        boolean deleted = userService.deleteUser(99L);

        assertThat(deleted).isFalse();
        verify(userRepository, never()).delete(any(User.class));
    }

    private UserRequest request(String email) {
        return new UserRequest(
                "Mario",
                "Rossi",
                email,
                "+39 333 1234567",
                new AddressDTO("Via Roma 1", "Roma", "RM", "Italia", "00100")
        );
    }
}
