package com.neonicz.miseva;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "miseva.invitations.expose-link=true")
@AutoConfigureMockMvc
class PlatformFlowTests {

    static final String DOMAIN = "@flowtest.local";
    static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;

    @Value("${spring.flyway.url}")
    String url;
    @Value("${spring.flyway.user}")
    String ownerUser;
    @Value("${spring.flyway.password}")
    String ownerPassword;

    JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
        TestCleanup.removeEverythingFor(owner, DOMAIN);
    }

    @AfterEach
    void tearDown() {
        TestCleanup.removeEverythingFor(owner, DOMAIN);
    }

    record Org(String businessId, String branchId, String email, String bearer, String refresh) {
    }

    private String email() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + DOMAIN;
    }

    private String registerJson(String email, String industry) {
        return """
                {"businessName":"Flowtest %s","industry":"%s","countryCode":"IN","currency":"INR","timezone":"Asia/Kolkata",
                 "ownerFirstName":"Asha","ownerLastName":"Rao","email":"%s","password":"%s"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), industry, email, PASSWORD);
    }

    private Org register() throws Exception {
        return register(email());
    }

    private Org register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/businesses/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, "restaurant")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Org(JsonPath.read(body, "$.businessId"), JsonPath.read(body, "$.headOfficeBranchId"), email,
                "Bearer " + JsonPath.<String>read(body, "$.tokens.accessToken"), JsonPath.read(body, "$.tokens.refreshToken"));
    }

    private void upgrade(Org org) {
        owner.update("UPDATE subscriptions SET plan_id = (SELECT id FROM plans WHERE code = 'scale') WHERE business_id = ?::uuid",
                org.businessId());
    }

    private String roleId(Org by, String code) throws Exception {
        String body = mvc.perform(get("/api/v1/roles/assignable").header("Authorization", by.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<String>>read(body, "$[?(@.code=='" + code + "')].id").get(0);
    }

    private ResultActions invite(Org by, String email, String role, String branches) throws Exception {
        return mvc.perform(post("/api/v1/employees/invitations").header("Authorization", by.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"firstName":"Dev","lastName":"Nair","email":"%s","jobTitle":"Waiter","roleIds":["%s"],%s}
                        """.formatted(email, role, branches)));
    }

    private static String tokenOf(ResultActions invited) throws Exception {
        String link = JsonPath.read(invited.andReturn().getResponse().getContentAsString(), "$.inviteUrl");
        return link.substring(link.indexOf("token=") + 6);
    }

    private ResultActions accept(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password)));
    }

    @Test
    void healthIsPublicAndOtherEndpointsNeedLogin() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(header().exists("X-Trace-Id"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationCreatesBusinessOwnerAndHeadOffice() throws Exception {
        Org org = register();
        mvc.perform(get("/api/v1/me").header("Authorization", org.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.businessId").value(org.businessId()))
                .andExpect(jsonPath("$.permissions", hasItem("rbac.role.manage")));
        mvc.perform(get("/api/v1/branches").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].code").value("HQ"))
                .andExpect(jsonPath("$.maxBranches").value(1));
        mvc.perform(get("/api/v1/businesses/current").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$.industry").value("restaurant"));
        mvc.perform(get("/api/v1/me").header("Authorization", org.bearer() + "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyRestaurantIsAvailableAndBadInputIsRejected() throws Exception {
        mvc.perform(post("/api/v1/businesses/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email(), "hotel")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_INDUSTRY"));
        mvc.perform(post("/api/v1/businesses/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("not-an-email", "restaurant")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String email = email();
        register(email);
        mvc.perform(post("/api/v1/businesses/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email.toUpperCase(), "restaurant")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void loginLockoutRefreshRotationAndLogout() throws Exception {
        Org org = register();
        String login = "{\"email\":\"%s\",\"password\":\"%s\"}";

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted(org.email(), PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted(org.email(), "wrong-password-1")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted("nobody" + DOMAIN, "wrong-password-1")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        String rotated = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(org.refresh())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String access2 = JsonPath.read(rotated, "$.accessToken");
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(org.refresh())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access2)).andExpect(status().isUnauthorized());

        Org again = register();
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(again.refresh())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", again.bearer())).andExpect(status().isUnauthorized());

        Org locked = register();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted(locked.email(), "wrong-password-1")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login.formatted(locked.email(), PASSWORD)))
                .andExpect(status().isLocked());
    }

    @Test
    void branchLifecycleWithPlanLimitAndVersioning() throws Exception {
        Org org = register();
        String create = "{\"name\":\"Kochi\",\"code\":\"kochi\",\"city\":\"Kochi\"}";
        mvc.perform(post("/api/v1/branches").header("Authorization", org.bearer()).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_LIMIT_REACHED"));

        upgrade(org);
        String created = mvc.perform(post("/api/v1/branches").header("Authorization", org.bearer()).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("KOCHI"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        int version = JsonPath.read(created, "$.version");

        mvc.perform(post("/api/v1/branches").header("Authorization", org.bearer()).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_CODE_TAKEN"));
        mvc.perform(put("/api/v1/branches/" + id).header("Authorization", org.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d,\"name\":\"Kochi Marine Drive\"}".formatted(version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Kochi Marine Drive"));
        mvc.perform(put("/api/v1/branches/" + id).header("Authorization", org.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d,\"name\":\"Stale\"}".formatted(version)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc.perform(post("/api/v1/branches/" + id + "/deactivate").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$.status").value("inactive"));
        mvc.perform(post("/api/v1/branches/" + org.branchId() + "/deactivate").header("Authorization", org.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HEAD_OFFICE_CANNOT_BE_DEACTIVATED"));
    }

    @Test
    void anotherBusinessCannotSeeOrChangeMyBranches() throws Exception {
        Org a = register();
        Org b = register();
        mvc.perform(get("/api/v1/branches/" + a.branchId()).header("Authorization", b.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/branches/" + a.branchId() + "/deactivate").header("Authorization", b.bearer()))
                .andExpect(status().isNotFound());
        String list = mvc.perform(get("/api/v1/branches").header("Authorization", b.bearer()))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(list).doesNotContain(a.branchId());
    }

    @Test
    void inviteAcceptGivesTheInviteeTheirRoleAndBranches() throws Exception {
        Org org = register();
        mvc.perform(get("/api/v1/roles/assignable").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$[*].code", hasItem("staff"))).andExpect(jsonPath("$[*].code", not(hasItem("owner"))));

        String inviteeEmail = email();
        String hq = "\"allBranches\":false,\"branchIds\":[\"%s\"]".formatted(org.branchId());
        String token = tokenOf(invite(org, inviteeEmail, roleId(org, "staff"), hq).andExpect(status().isCreated()));

        mvc.perform(get("/api/v1/employees").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.email=='" + inviteeEmail + "')].status").value("invited"));
        mvc.perform(get("/api/v1/invitations/preview").param("token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.existingAccount").value(false));
        accept(token, "short").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));

        String accepted = accept(token, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String staff = "Bearer " + JsonPath.<String>read(accepted, "$.accessToken");
        mvc.perform(get("/api/v1/me").header("Authorization", staff))
                .andExpect(jsonPath("$.permissions", hasItem("business.branch.read")))
                .andExpect(jsonPath("$.permissions", not(hasItem("employee.member.invite"))));
        mvc.perform(get("/api/v1/employees").header("Authorization", staff)).andExpect(status().isForbidden());
        invite(new Org(org.businessId(), org.branchId(), inviteeEmail, staff, ""), email(), roleId(org, "staff"), "\"allBranches\":true")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        accept(token, PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
    }

    @Test
    void invitationGuardrailsExpiryRevokeLimitAndEscalation() throws Exception {
        Org org = register();
        String all = "\"allBranches\":true";
        String ownerRole = owner.queryForObject("SELECT id::text FROM roles WHERE business_id IS NULL AND code = 'owner'", String.class);
        invite(org, email(), ownerRole, all).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_ASSIGNABLE"));

        String inviteeEmail = email();
        ResultActions first = invite(org, inviteeEmail, roleId(org, "manager"), all).andExpect(status().isCreated());
        String oldToken = tokenOf(first);
        String membershipId = JsonPath.read(first.andReturn().getResponse().getContentAsString(), "$.membershipId");

        owner.update("UPDATE business_memberships SET invitation_expires_at = now() - interval '1 minute' WHERE id = ?::uuid", membershipId);
        mvc.perform(get("/api/v1/invitations/preview").param("token", oldToken)).andExpect(status().isBadRequest());

        String newToken = tokenOf(mvc.perform(post("/api/v1/employees/" + membershipId + "/resend").header("Authorization", org.bearer()))
                .andExpect(status().isOk()));
        mvc.perform(get("/api/v1/invitations/preview").param("token", oldToken)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/invitations/preview").param("token", newToken)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/employees/" + membershipId + "/revoke").header("Authorization", org.bearer()))
                .andExpect(status().isNoContent());
        accept(newToken, PASSWORD).andExpect(status().isBadRequest());

        owner.update("INSERT INTO plans (code, name, billing_interval) VALUES ('test-tiny', 'Tiny', 'monthly')");
        owner.update("INSERT INTO plan_entitlements (plan_id, feature_key, limit_value)"
                + " SELECT id, 'max_employees', 1 FROM plans WHERE code = 'test-tiny'");
        owner.update("UPDATE subscriptions SET plan_id = (SELECT id FROM plans WHERE code = 'test-tiny') WHERE business_id = ?::uuid",
                org.businessId());
        try {
            invite(org, email(), roleId(org, "staff"), all)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMPLOYEE_LIMIT_REACHED"));
        } finally {
            owner.update("UPDATE subscriptions SET plan_id = (SELECT id FROM plans WHERE code = 'starter') WHERE business_id = ?::uuid",
                    org.businessId());
            owner.update("DELETE FROM plan_entitlements WHERE plan_id = (SELECT id FROM plans WHERE code = 'test-tiny')");
            owner.update("DELETE FROM plans WHERE code = 'test-tiny'");
        }
    }

    @Test
    void existingAccountJoinsAnotherBusinessAndMustChooseWhenSigningIn() throws Exception {
        Org a = register();
        String shared = email();
        register(shared);

        String token = tokenOf(invite(a, shared, roleId(a, "manager"), "\"allBranches\":true").andExpect(status().isCreated()));
        mvc.perform(get("/api/v1/invitations/preview").param("token", token))
                .andExpect(jsonPath("$.existingAccount").value(true));
        accept(token, "wrong-password-123").andExpect(status().isUnauthorized());
        accept(token, PASSWORD).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(shared, PASSWORD)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BUSINESS_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.businesses.length()").value(2));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"businessId\":\"%s\"}".formatted(shared, PASSWORD, a.businessId())))
                .andExpect(status().isOk());
    }
}
