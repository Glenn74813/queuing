package ph.gov.philhealth.qms.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.config.QmsProperties;

/**
 * Creates the first Super Administrator ("superadmin") when the user table is empty and
 * QMS_BOOTSTRAP_ADMIN_PASSWORD is set. The password must be changed at first login; remove the
 * variable from the environment afterwards.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PasswordEncoder encoder;
    private final QmsProperties props;

    public BootstrapAdminInitializer(AppUserRepository users, AppRoleRepository roles, PasswordEncoder encoder,
                                     QmsProperties props) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = props.security().bootstrapAdminPassword();
        if (users.count() > 0 || password == null || password.isBlank()) {
            return;
        }
        PasswordPolicy.validate(password, "superadmin");
        AppUser u = new AppUser();
        u.setId(Ids.newId());
        u.setUsername("superadmin");
        u.setFullName("Super Administrator");
        u.setPasswordHash(encoder.encode(password));
        u.setStatus(AppUser.Status.ACTIVE);
        u.setMustChangePassword(true);
        RoleAssignment a = new RoleAssignment();
        a.setId(Ids.newId());
        a.setUser(u);
        a.setRole(roles.findById("SUPER_ADMIN").orElseThrow());
        u.getAssignments().add(a);
        users.save(u);
        log.warn("Bootstrap Super Administrator 'superadmin' created. Change its password at first login "
                + "and unset QMS_BOOTSTRAP_ADMIN_PASSWORD.");
    }
}
