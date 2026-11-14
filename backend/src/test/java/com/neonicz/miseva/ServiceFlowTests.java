package com.neonicz.miseva;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class ServiceFlowTests {

    static final String DOMAIN = "@servicetest.local";

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

    record Shop(String bearer, String branch, String dish, String t1, String t2, String token1, String token2) {
    }

    record Staff(String bearer, String membershipId) {
    }

    private ResultActions send(String bearer, String method, String path, String json) throws Exception {
        var req = ("PUT".equals(method) ? put(path) : post(path)).contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(bearer == null ? req : req.header("Authorization", bearer));
    }

    private ResultActions fetch(String bearer, String path) throws Exception {
        return mvc.perform(bearer == null ? get(path) : get(path).header("Authorization", bearer));
    }

    private static String read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path).toString();
    }

    private Shop shop() throws Exception {
        ResultActions reg = send(null, "POST", "/api/v1/businesses/register", """
                {"businessName":"Servicetest %s","industry":"restaurant","countryCode":"IN","currency":"INR",
                 "timezone":"Asia/Kolkata","ownerFirstName":"Asha","ownerLastName":"Rao","email":"u%s%s","password":"correct-horse-battery"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID().toString().substring(0, 8), DOMAIN))
                .andExpect(status().isCreated());
        String bearer = "Bearer " + read(reg, "$.tokens.accessToken");
        String branch = read(reg, "$.headOfficeBranchId");
        String cat = read(send(bearer, "POST", "/api/v1/restaurant/menu/categories", "{\"name\":\"Mains\",\"sortOrder\":0}"), "$.id");
        String dish = read(send(bearer, "POST", "/api/v1/restaurant/menu/items",
                "{\"categoryId\":\"%s\",\"name\":\"Idli\",\"priceMinor\":8000,\"taxRateBp\":500,\"sortOrder\":0}".formatted(cat)), "$.id");
        String t1 = read(send(bearer, "POST", "/api/v1/restaurant/branches/" + branch + "/tables", "{\"code\":\"T1\",\"seats\":4}"), "$.id");
        String t2 = read(send(bearer, "POST", "/api/v1/restaurant/branches/" + branch + "/tables", "{\"code\":\"T2\",\"seats\":4}"), "$.id");
        String tables = fetch(bearer, "/api/v1/restaurant/branches/" + branch + "/tables").andReturn().getResponse().getContentAsString();
        return new Shop(bearer, branch, dish, t1, t2, JsonPath.read(tables, "$[0].qrToken"), JsonPath.read(tables, "$[1].qrToken"));
    }

    private Staff staff(Shop s) throws Exception {
        String roles = fetch(s.bearer(), "/api/v1/roles/assignable").andReturn().getResponse().getContentAsString();
        String staffRole = JsonPath.<List<String>>read(roles, "$[?(@.code=='staff')].id").get(0);
        ResultActions invite = send(s.bearer(), "POST", "/api/v1/employees/invitations",
                "{\"firstName\":\"Waiter\",\"lastName\":\"%s\",\"email\":\"w%s%s\",\"roleIds\":[\"%s\"],\"allBranches\":true}"
                        .formatted(UUID.randomUUID().toString().substring(0, 4), UUID.randomUUID().toString().substring(0, 8), DOMAIN, staffRole));
        String link = read(invite, "$.inviteUrl");
        String access = read(send(null, "POST", "/api/v1/invitations/accept",
                "{\"token\":\"%s\",\"password\":\"correct-horse-battery\"}".formatted(link.substring(link.indexOf("token=") + 6))), "$.accessToken");
        return new Staff("Bearer " + access, read(invite, "$.membershipId"));
    }

    private String guestOrder(Shop s, String token) throws Exception {
        return read(send(null, "POST", "/api/v1/public/tables/" + token + "/orders",
                "{\"guests\":2,\"lines\":[{\"menuItemId\":\"%s\",\"quantity\":2}]}".formatted(s.dish())).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions alerts(String bearer, Shop s) throws Exception {
        return fetch(bearer, "/api/v1/restaurant/branches/" + s.branch() + "/alerts");
    }

    @Test
    void alertsGoToTheAssignedWaiterAndManagersSeeEverything() throws Exception {
        Shop s = shop();
        Staff a = staff(s);
        Staff b = staff(s);

        fetch(s.bearer(), "/api/v1/restaurant/branches/" + s.branch() + "/waiters")
                .andExpect(jsonPath("$[*].membershipId", hasItem(a.membershipId())));
        send(s.bearer(), "PUT", "/api/v1/restaurant/tables/" + s.t1() + "/waiter", "{\"membershipId\":\"%s\"}".formatted(a.membershipId()))
                .andExpect(status().isNoContent());
        send(s.bearer(), "PUT", "/api/v1/restaurant/tables/" + s.t2() + "/waiter", "{\"membershipId\":\"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_WAITER"));
        send(a.bearer(), "PUT", "/api/v1/restaurant/tables/" + s.t2() + "/waiter", "{\"membershipId\":null}")
                .andExpect(status().isForbidden());
        fetch(s.bearer(), "/api/v1/restaurant/branches/" + s.branch() + "/tables")
                .andExpect(jsonPath("$[0].waiterName").value(org.hamcrest.Matchers.startsWith("Waiter")));

        String order1 = guestOrder(s, s.token1());
        guestOrder(s, s.token2());

        alerts(a.bearer(), s).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.tableCode=='T1')].mine").value(true));
        alerts(b.bearer(), s).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].tableCode").value("T2"));
        alerts(s.bearer(), s).andExpect(jsonPath("$.length()").value(2));

        String session = read(send(a.bearer(), "POST", "/api/v1/restaurant/guest-orders/" + order1 + "/accept", ""), "$.sessionId");
        String line = read(fetch(a.bearer(), "/api/v1/restaurant/sessions/" + session), "$.lines[0].id");
        send(a.bearer(), "POST", "/api/v1/restaurant/lines/" + line + "/status", "{\"status\":\"preparing\"}");
        send(a.bearer(), "POST", "/api/v1/restaurant/lines/" + line + "/status", "{\"status\":\"ready\"}");
        alerts(a.bearer(), s).andExpect(jsonPath("$[?(@.kind=='ready')].detail").value("2 × Idli"));
        alerts(b.bearer(), s).andExpect(jsonPath("$[*].kind", not(hasItem("ready"))));
    }

    @Test
    void guestSeesEachItemProgressAfterAcceptance() throws Exception {
        Shop s = shop();
        String order = guestOrder(s, s.token1());
        fetch(null, "/api/v1/public/tables/" + s.token1() + "/orders/" + order)
                .andExpect(jsonPath("$.status").value("pending")).andExpect(jsonPath("$.items[0].status").value("pending"));

        String session = read(send(s.bearer(), "POST", "/api/v1/restaurant/guest-orders/" + order + "/accept", ""), "$.sessionId");
        fetch(null, "/api/v1/public/tables/" + s.token1() + "/orders/" + order)
                .andExpect(jsonPath("$.status").value("accepted")).andExpect(jsonPath("$.items[0].status").value("new"))
                .andExpect(jsonPath("$.items[0].itemName").value("Idli"));
        String line = read(fetch(s.bearer(), "/api/v1/restaurant/sessions/" + session), "$.lines[0].id");
        for (String next : new String[] {"preparing", "ready", "served"}) {
            send(s.bearer(), "POST", "/api/v1/restaurant/lines/" + line + "/status", "{\"status\":\"%s\"}".formatted(next));
            fetch(null, "/api/v1/public/tables/" + s.token1() + "/orders/" + order).andExpect(jsonPath("$.items[0].status").value(next));
        }
    }

    @Test
    void callWaiterAndRequestBillAreDeduplicatedAndResolvable() throws Exception {
        Shop s = shop();
        String path = "/api/v1/public/tables/" + s.token1() + "/requests";
        send(null, "POST", path, "{\"type\":\"call_waiter\"}").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));
        send(null, "POST", path, "{\"type\":\"call_waiter\"}").andExpect(jsonPath("$.created").value(false));
        send(null, "POST", path, "{\"type\":\"request_bill\"}").andExpect(jsonPath("$.created").value(true));
        send(null, "POST", path, "{\"type\":\"dance\"}").andExpect(status().isBadRequest());
        send(null, "POST", "/api/v1/public/tables/" + "0".repeat(64) + "/requests", "{\"type\":\"call_waiter\"}").andExpect(status().isNotFound());

        fetch(s.bearer(), "/api/v1/restaurant/branches/" + s.branch() + "/tables").andExpect(jsonPath("$[0].openRequests").value(2));
        String call = read(alerts(s.bearer(), s), "$[?(@.kind=='call_waiter')].id").replaceAll("[\\[\\]\"]", "");

        Shop other = shop();
        send(other.bearer(), "POST", "/api/v1/restaurant/service-requests/" + call + "/done", "").andExpect(status().isNotFound());

        send(s.bearer(), "POST", "/api/v1/restaurant/service-requests/" + call + "/done", "").andExpect(status().isNoContent());
        alerts(s.bearer(), s).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].kind").value("request_bill"));
        send(null, "POST", path, "{\"type\":\"call_waiter\"}").andExpect(jsonPath("$.created").value(true));
    }
}
