package com.neonicz.miseva;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
class RestaurantFlowTests {

    static final String DOMAIN = "@restotest.local";
    static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;
    @Autowired
    DataSource appDataSource;

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

    record Org(String businessId, String branchId, String bearer) {
    }

    private Org register() throws Exception {
        String body = mvc.perform(post("/api/v1/businesses/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Restotest %s","industry":"restaurant","countryCode":"IN","currency":"INR",
                                 "timezone":"Asia/Kolkata","ownerFirstName":"Asha","ownerLastName":"Rao","email":"u%s%s","password":"%s"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID().toString().substring(0, 8),
                                DOMAIN, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Org(JsonPath.read(body, "$.businessId"), JsonPath.read(body, "$.headOfficeBranchId"),
                "Bearer " + JsonPath.<String>read(body, "$.tokens.accessToken"));
    }

    private ResultActions postJson(Org by, String path, String json) throws Exception {
        return mvc.perform(post(path).header("Authorization", by.bearer()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String id(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    private String category(Org org, String name) throws Exception {
        return id(postJson(org, "/api/v1/restaurant/menu/categories", "{\"name\":\"%s\",\"sortOrder\":0}".formatted(name))
                .andExpect(status().isCreated()), "$.id");
    }

    private String item(Org org, String categoryId, String name, long price, int taxBp) throws Exception {
        return id(postJson(org, "/api/v1/restaurant/menu/items",
                "{\"categoryId\":\"%s\",\"name\":\"%s\",\"priceMinor\":%d,\"taxRateBp\":%d,\"sortOrder\":0}"
                        .formatted(categoryId, name, price, taxBp)).andExpect(status().isCreated()), "$.id");
    }

    private String table(Org org, String branchId, String code) throws Exception {
        return id(postJson(org, "/api/v1/restaurant/branches/" + branchId + "/tables",
                "{\"code\":\"%s\",\"seats\":4}".formatted(code)).andExpect(status().isCreated()), "$.id");
    }

    private String open(Org org, String tableId) throws Exception {
        return id(postJson(org, "/api/v1/restaurant/tables/" + tableId + "/sessions", "{\"guests\":2}")
                .andExpect(status().isCreated()), "$.session.id");
    }

    private ResultActions addLines(Org org, String sessionId, String linesJson) throws Exception {
        return postJson(org, "/api/v1/restaurant/sessions/" + sessionId + "/lines", "{\"lines\":[" + linesJson + "]}");
    }

    private ResultActions move(Org org, String lineId, String to) throws Exception {
        return postJson(org, "/api/v1/restaurant/lines/" + lineId + "/status", "{\"status\":\"%s\"}".formatted(to));
    }

    @Test
    void fullDineInFlowFromOrderToPaidBill() throws Exception {
        Org org = register();
        String mains = category(org, "Mains");
        String biryani = item(org, mains, "Chicken Biryani", 25_000, 500);
        String lime = item(org, mains, "Fresh Lime Soda", 12_000, 1_800);
        String t1 = table(org, org.branchId(), "t1");

        String session = open(org, t1);
        postJson(org, "/api/v1/restaurant/tables/" + t1 + "/sessions", "{\"guests\":2}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_OCCUPIED"));

        String afterAdd = addLines(org, session, "{\"menuItemId\":\"%s\",\"quantity\":2},{\"menuItemId\":\"%s\",\"quantity\":1,\"note\":\"less ice\"}"
                .formatted(biryani, lime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalMinor").value(62_000))
                .andExpect(jsonPath("$.taxMinor").value(4_660))
                .andExpect(jsonPath("$.totalMinor").value(66_660))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andReturn().getResponse().getContentAsString();
        List<String> lineIds = JsonPath.read(afterAdd, "$.lines[*].id");

        mvc.perform(get("/api/v1/restaurant/branches/" + org.branchId() + "/tables").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$[0].code").value("T1")).andExpect(jsonPath("$[0].sessionStatus").value("open"));
        mvc.perform(get("/api/v1/restaurant/branches/" + org.branchId() + "/kitchen").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].tableCode").value("T1"));

        move(org, lineIds.get(0), "served").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_LINE_STATUS"));
        move(org, lineIds.get(0), "preparing").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("preparing"));
        move(org, lineIds.get(0), "ready").andExpect(status().isOk());
        move(org, lineIds.get(0), "served").andExpect(status().isOk());
        mvc.perform(get("/api/v1/restaurant/branches/" + org.branchId() + "/kitchen").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$.length()").value(1));

        String billed = postJson(org, "/api/v1/restaurant/sessions/" + session + "/bill", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("billed"))
                .andExpect(jsonPath("$.bill.bill.billNumber").value("B-000001"))
                .andExpect(jsonPath("$.bill.bill.totalMinor").value(66_660))
                .andExpect(jsonPath("$.bill.lines.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String billId = JsonPath.read(billed, "$.bill.bill.id");

        addLines(org, session, "{\"menuItemId\":\"%s\",\"quantity\":1}".formatted(lime))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_NOT_OPEN"));
        postJson(org, "/api/v1/restaurant/sessions/" + session + "/bill", "").andExpect(status().isConflict());

        postJson(org, "/api/v1/restaurant/bills/" + billId + "/payments", "{\"method\":\"cash\",\"amountMinor\":60000}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.bill.dueMinor").value(6_660))
                .andExpect(jsonPath("$.session.status").value("billed"));
        postJson(org, "/api/v1/restaurant/bills/" + billId + "/payments", "{\"method\":\"card\",\"amountMinor\":10000}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OVERPAYMENT"));
        postJson(org, "/api/v1/restaurant/bills/" + billId + "/payments", "{\"method\":\"upi\",\"amountMinor\":6660,\"reference\":\"UPI-123\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.bill.bill.status").value("paid"))
                .andExpect(jsonPath("$.bill.dueMinor").value(0)).andExpect(jsonPath("$.session.status").value("closed"));

        mvc.perform(get("/api/v1/restaurant/branches/" + org.branchId() + "/tables").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$[0].sessionId").doesNotExist());
        open(org, t1);
    }

    @Test
    void cancellationAndAvailabilityRules() throws Exception {
        Org org = register();
        String cat = category(org, "Starters");
        String soup = item(org, cat, "Tomato Soup", 9_000, 500);
        String t = table(org, org.branchId(), "T2");
        String session = open(org, t);

        String added = addLines(org, session, "{\"menuItemId\":\"%s\",\"quantity\":1},{\"menuItemId\":\"%s\",\"quantity\":1}"
                .formatted(soup, soup)).andReturn().getResponse().getContentAsString();
        List<String> lines = JsonPath.read(added, "$.lines[*].id");
        move(org, lines.get(0), "cancelled").andExpect(status().isOk());
        move(org, lines.get(1), "preparing").andExpect(status().isOk());
        move(org, lines.get(1), "cancelled").andExpect(status().isConflict());

        mvc.perform(put("/api/v1/restaurant/menu/items/" + soup)
                        .header("Authorization", org.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"%s\",\"name\":\"Tomato Soup\",\"priceMinor\":9000,\"taxRateBp\":500,\"available\":false,\"sortOrder\":0}"
                                .formatted(cat)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        addLines(org, session, "{\"menuItemId\":\"%s\",\"quantity\":1}".formatted(soup))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));

        String empty = open(org, table(org, org.branchId(), "T3"));
        postJson(org, "/api/v1/restaurant/sessions/" + empty + "/bill", "")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTHING_TO_BILL"));
    }

    @Test
    void staffTakeOrdersButCannotManageMenuOrBillAndSeeOnlyTheirBranch() throws Exception {
        Org org = register();
        owner.update("UPDATE subscriptions SET plan_id = (SELECT id FROM plans WHERE code = 'scale') WHERE business_id = ?::uuid",
                org.businessId());
        String goa = id(postJson(org, "/api/v1/branches", "{\"name\":\"Goa\",\"code\":\"GOA\"}").andExpect(status().isCreated()), "$.id");
        String cat = category(org, "Mains");
        String dish = item(org, cat, "Fish Curry", 30_000, 500);
        String hqTable = table(org, org.branchId(), "T1");
        String goaTable = table(org, goa, "G1");

        String roles = mvc.perform(get("/api/v1/roles/assignable").header("Authorization", org.bearer()))
                .andReturn().getResponse().getContentAsString();
        String staffRole = JsonPath.<List<String>>read(roles, "$[?(@.code=='staff')].id").get(0);
        String invite = postJson(org, "/api/v1/employees/invitations",
                "{\"firstName\":\"Dev\",\"lastName\":\"Nair\",\"email\":\"w%s%s\",\"roleIds\":[\"%s\"],\"allBranches\":false,\"branchIds\":[\"%s\"]}"
                        .formatted(UUID.randomUUID().toString().substring(0, 8), DOMAIN, staffRole, org.branchId()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invite, "$.inviteUrl");
        String accepted = mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(link.substring(link.indexOf("token=") + 6), PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Org staff = new Org(org.businessId(), org.branchId(), "Bearer " + JsonPath.<String>read(accepted, "$.accessToken"));

        String session = open(staff, hqTable);
        String added = addLines(staff, session, "{\"menuItemId\":\"%s\",\"quantity\":1}".formatted(dish))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        move(staff, JsonPath.<List<String>>read(added, "$.lines[*].id").get(0), "preparing").andExpect(status().isOk());

        postJson(staff, "/api/v1/restaurant/menu/categories", "{\"name\":\"Hack\",\"sortOrder\":0}").andExpect(status().isForbidden());
        postJson(staff, "/api/v1/restaurant/sessions/" + session + "/bill", "").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/restaurant/branches/" + goa + "/tables").header("Authorization", staff.bearer()))
                .andExpect(status().isNotFound());
        postJson(staff, "/api/v1/restaurant/tables/" + goaTable + "/sessions", "{\"guests\":2}").andExpect(status().isNotFound());
    }

    @Test
    void anotherRestaurantCannotTouchMyOrdersBillsOrMenu() throws Exception {
        Org a = register();
        Org b = register();
        String dish = item(a, category(a, "Mains"), "Paneer Tikka", 20_000, 500);
        String session = open(a, table(a, a.branchId(), "T1"));
        addLines(a, session, "{\"menuItemId\":\"%s\",\"quantity\":1}".formatted(dish)).andExpect(status().isOk());
        String billId = id(postJson(a, "/api/v1/restaurant/sessions/" + session + "/bill", "").andExpect(status().isOk()), "$.bill.bill.id");

        mvc.perform(get("/api/v1/restaurant/sessions/" + session).header("Authorization", b.bearer())).andExpect(status().isNotFound());
        addLines(b, session, "{\"menuItemId\":\"%s\",\"quantity\":1}".formatted(dish)).andExpect(status().isNotFound());
        postJson(b, "/api/v1/restaurant/bills/" + billId + "/payments", "{\"method\":\"cash\",\"amountMinor\":100}")
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/restaurant/branches/" + a.branchId() + "/kitchen").header("Authorization", b.bearer()))
                .andExpect(status().isNotFound());
        String bSession = open(b, table(b, b.branchId(), "T1"));
        addLines(b, bSession, "{\"menuItemId\":\"%s\",\"quantity\":1}".formatted(dish))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));
        mvc.perform(get("/api/v1/restaurant/menu").header("Authorization", b.bearer()))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void paymentsAndBillLinesCannotBeEditedOrDeletedByTheApp() {
        JdbcTemplate app = new JdbcTemplate(appDataSource);
        assertThatThrownBy(() -> app.update("UPDATE payments SET amount_minor = 1"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.update("UPDATE bill_lines SET quantity = 1"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM bills"))
                .rootCause().hasMessageContaining("permission denied");
    }
}
