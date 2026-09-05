package com.app.user_service.service;

import com.app.user_service.dto.UserRequest;
import com.app.user_service.dto.UserResponse;
import com.app.user_service.mapper.UserMapper;
import com.app.user_service.model.User;
import com.app.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public List<UserResponse> fetchAllUsers() {
        return userRepository.findAll().stream()
                .map(userMapper::toResponse)
                .toList();
    }

    @Transactional
    public UserResponse addUser(UserRequest request) {
        User user = userMapper.toEntity(request);
        normalizeEmail(user);
        User savedUser = userRepository.saveAndFlush(user);
        return userMapper.toResponse(savedUser);
    }

    public Optional<UserResponse> fetchUser(Long id) {
        return userRepository.findById(id)
                .map(userMapper::toResponse);
    }

    @Transactional
    public Optional<UserResponse> updateUser(Long id, UserRequest request) {
        return userRepository.findById(id)
                .map(existingUser -> {
                    userMapper.updateEntity(request, existingUser);
                    normalizeEmail(existingUser);
                    User savedUser = userRepository.saveAndFlush(existingUser);
                    return userMapper.toResponse(savedUser);
                });
    }

    @Transactional
    public boolean deleteUser(Long id) {
        return userRepository.findById(id)
                .map(user -> {
                    userRepository.delete(user);
                    return true;
                })
                .orElse(false);
    }

    private void normalizeEmail(User user) {
        if (user.getEmail() != null) {
            user.setEmail(user.getEmail().strip().toLowerCase(Locale.ROOT));
        }
    }
}
