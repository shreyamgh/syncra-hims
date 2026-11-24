package com.syncra.hims.iam.account;

import com.syncra.hims.iam.account.AccountRepository.StaffMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Account administration (administrators only): {@code /api/v1/iam/users}, {@code /api/v1/iam/staff}. */
@RestController
@RequestMapping("/api/v1/iam")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminController {

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$", message = "3-64 letters, digits, '.', '_' or '-'") String username,
            @Size(max = 150) String displayName,
            UUID staffId,
            @NotEmpty @Size(max = 4) List<@NotBlank String> roles) {
    }

    public record RolesRequest(@NotEmpty @Size(max = 4) List<@NotBlank String> roles) {
    }

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/users")
    public List<AdminService.AccountView> users() {
        return admin.list();
    }

    @GetMapping("/staff")
    public List<StaffMember> staff() {
        return admin.staff();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminService.Created create(@Valid @RequestBody CreateRequest r) {
        return admin.create(r.username(), r.displayName(), r.staffId(), r.roles());
    }

    @PostMapping("/users/{id}/reset-password")
    public AdminService.Created reset(@PathVariable UUID id) {
        return admin.resetPassword(id);
    }

    @PostMapping("/users/{id}/disable")
    public AdminService.AccountView disable(@PathVariable UUID id) {
        return admin.setStatus(id, false);
    }

    @PostMapping("/users/{id}/enable")
    public AdminService.AccountView enable(@PathVariable UUID id) {
        return admin.setStatus(id, true);
    }

    @PostMapping("/users/{id}/unlock")
    public AdminService.AccountView unlock(@PathVariable UUID id) {
        return admin.unlock(id);
    }

    @PutMapping("/users/{id}/roles")
    public AdminService.AccountView roles(@PathVariable UUID id, @Valid @RequestBody RolesRequest r) {
        return admin.setRoles(id, r.roles());
    }
}
