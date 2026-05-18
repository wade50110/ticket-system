package com.example.ticket.auth.dto;

import com.example.ticket.auth.User;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserInfo {

    private Long id;
    private String username;
    private String email;
    private String name;

    public static UserInfo from(User user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getEmail(), user.getName());
    }
}
