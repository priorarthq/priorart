package io.priorart.directory;

import io.priorart.domain.User;
import io.priorart.domain.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Users are auto-provisioned from the handle the capture hook sends: capture never fails on missing setup. */
@Service
public class UserService {
    @PersistenceContext private EntityManager em;
    private final UserRepository users;

    public UserService(UserRepository users) { this.users = users; }

    @Transactional(propagation = Propagation.REQUIRED)
    public User resolve(String handle) {
        String h = handle == null || handle.isBlank() ? "unknown" : handle.trim().toLowerCase(java.util.Locale.ROOT);
        // Race-safe upsert: concurrent hooks for the same new user must not poison the ingest transaction.
        em.createNativeQuery("insert into users (handle) values (:h) on conflict (handle) do nothing")
                .setParameter("h", h).executeUpdate();
        return users.findByHandle(h).orElseThrow();
    }
}
