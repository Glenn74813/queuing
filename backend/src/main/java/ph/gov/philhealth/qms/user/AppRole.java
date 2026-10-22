package ph.gov.philhealth.qms.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeType;

@Entity
@Table(name = "app_role")
@Getter
@Setter
public class AppRole {

    @Id
    private String code;
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type")
    private ScopeType scopeType;

    private int level;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_code"))
    @Column(name = "permission")
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);
}
