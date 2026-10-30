package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByHandle(String handle);
    Optional<User> findByTokenSha256(String tokenSha256);
}
