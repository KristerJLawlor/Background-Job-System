package com.krister.avatar.api;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service

//Is meant to ensure that API uses the external avatar path defined in application.properties, and not a hardcoded path. 
// This allows for better flexibility and configurability of the application, as the avatar storage location can be easily changed without modifying the code.
public class AvatarService {

    @Value("${avatars.path}")
    private String avatarDir;

    public Path getAvatarOutputPath(String fileName) {
        return Paths.get(avatarDir, fileName);
    }
}
