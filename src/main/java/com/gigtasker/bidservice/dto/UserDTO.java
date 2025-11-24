package com.gigtasker.bidservice.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class UserDTO {
    private Long id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private UUID keycloakId;
}
