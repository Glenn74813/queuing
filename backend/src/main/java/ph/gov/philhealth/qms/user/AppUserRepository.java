package ph.gov.philhealth.qms.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @Query("select u from AppUser u where lower(u.username) = lower(:username)")
    Optional<AppUser> findByUsernameIgnoreCase(@Param("username") String username);

    @Query("""
            select u from AppUser u
            where (:q = '' or lower(u.username) like :q or lower(u.fullName) like :q)
              and (:national = true or exists (
                    select a from RoleAssignment a
                    where a.user = u and ((a.site is not null and a.site.id in :siteIds)
                                       or (a.region is not null and a.region.id in :regionIds))))
            """)
    Page<AppUser> search(@Param("q") String q, @Param("national") boolean national,
                         @Param("siteIds") java.util.Collection<UUID> siteIds,
                         @Param("regionIds") java.util.Collection<UUID> regionIds, Pageable pageable);
}
