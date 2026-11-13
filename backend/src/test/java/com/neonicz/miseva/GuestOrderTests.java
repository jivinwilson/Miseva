package com.neonicz.miseva;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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

@SpringBootTest
@AutoConfigureMockMvc
class GuestOrderTests {

    static final String DOMAIN = "@guesttest.local";

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

    record Shop(String bearer, String branchId, String tableId, String token, String dish, String categoryId) {
    }

    private ResultActions postJson(String bearer, String path, String json) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(bearer == null ? req : req.header("Authorization", bearer));
    }

    private static String read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path).toString();
    }

    private Shop shop() throws Exception {
        ResultActions reg = postJson(null, "/api/v1/businesses/register", """
                {"businessName":"Guesttest %s","industry":"restaurant","countryCode":"IN","currency":"INR",
                 "timezone":"Asia/Kolkata","ownerFirstName":"Asha","ownerLastName":"Rao","email":"u%s%s","password":"correct-horse-battery"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID().toString().substring(0, 8), DOMAIN))
                .andExpect(status().isCreated());
        String bearer = "Bearer " + read(reg, "$.tokens.accessToken");
        String branch = read(reg, "$.headOfficeBranchId");
        String cat = read(postJson(bearer, "/api/v1/restaurant/menu/categories", "{\"name\":\"Mains\",\"sortOrder\":0}"), "$.id");
        String dish = read(postJson(bearer, "/api/v1/restaurant/menu/items",
                "{\"categoryId\":\"%s\",\"name\":\"Masala Dosa\",\"priceMinor\":15000,\"taxRateBp\":500,\"sortOrder\":0}".formatted(cat)), "$.id");
        String table = read(postJson(bearer, "/api/v1/restaurant/branches/" + branch + "/tables", "{\"code\":\"T1\",\"seats\":4}"), "$.id");
        String token = read(mvc.perform(get("/api/v1/restaurant/branches/" + branch + "/tables").header("Authorization", bearer)), "$[0].qrToken");
        return new Shop(bearer, branch, table, token, dish, cat);
    }

    private ResultActions guestOrder(Shop s, int qty) throws Exception {
        return postJson(null, "/api/v1/public/tables/" + s.token() + "/orders",
                "{\"guests\":3,\"note\":\"Birthday\",\"lines\":[{\"menuItemId\":\"%s\",\"quantity\":%d,\"note\":\"Crispy\"}]}".formatted(s.dish(), qty));
    }

    @Test
    void guestOrdersThroughQrAndStaffAcceptsIntoTheKitchen() throws Exception {
        Shop s = shop();
        mvc.perform(get("/api/v1/public/tables/" + s.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tableCode").value("T1"))
                .andExpect(jsonPath("$.currency").value("INR")).andExpect(jsonPath("$.items[0].name").value("Masala Dosa"));

        String orderId = read(guestOrder(s, 2).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending")), "$.id");
        mvc.perform(get("/api/v1/restaurant/branches/" + s.branchId() + "/tables").header("Authorization", s.bearer()))
                .andExpect(jsonPath("$[0].pendingGuestOrders").value(1)).andExpect(jsonPath("$[0].sessionId").doesNotExist());
        mvc.perform(get("/api/v1/restaurant/branches/" + s.branchId() + "/guest-orders").header("Authorization", s.bearer()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].order.guests").value(3))
                .andExpect(jsonPath("$[0].lines[0].quantity").value(2));

        String session = read(postJson(s.bearer(), "/api/v1/restaurant/guest-orders/" + orderId + "/accept", "")
                .andExpect(status().isOk()), "$.sessionId");
        mvc.perform(get("/api/v1/public/tables/" + s.token() + "/orders/" + orderId)).andExpect(jsonPath("$.status").value("accepted"));
        mvc.perform(get("/api/v1/restaurant/sessions/" + session).header("Authorization", s.bearer()))
                .andExpect(jsonPath("$.session.guests").value(3)).andExpect(jsonPath("$.lines[0].note").value("Crispy"))
                .andExpect(jsonPath("$.totalMinor").value(31_500));
        postJson(s.bearer(), "/api/v1/restaurant/guest-orders/" + orderId + "/accept", "")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GUEST_ORDER_DECIDED"));

        String second = read(guestOrder(s, 1).andExpect(status().isCreated()), "$.id");
        String sameSession = read(postJson(s.bearer(), "/api/v1/restaurant/guest-orders/" + second + "/accept", "")
                .andExpect(status().isOk()), "$.sessionId");
        org.assertj.core.api.Assertions.assertThat(sameSession).isEqualTo(session);
        mvc.perform(get("/api/v1/restaurant/branches/" + s.branchId() + "/kitchen").header("Authorization", s.bearer()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void rejectionPendingLimitAndUnavailableItems() throws Exception {
        Shop s = shop();
        String first = read(guestOrder(s, 1), "$.id");
        guestOrder(s, 1).andExpect(status().isCreated());
        guestOrder(s, 1).andExpect(status().isCreated());
        guestOrder(s, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_MANY_PENDING"));

        postJson(s.bearer(), "/api/v1/restaurant/guest-orders/" + first + "/reject", "").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/tables/" + s.token() + "/orders/" + first)).andExpect(jsonPath("$.status").value("rejected"));
        guestOrder(s, 1).andExpect(status().isCreated());

        guestOrder(s, 21).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        owner.update("UPDATE menu_items SET is_available = false WHERE id = ?::uuid", s.dish());
        mvc.perform(get("/api/v1/public/tables/" + s.token())).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void badInactiveOrRegeneratedTokensFindNothing() throws Exception {
        Shop s = shop();
        mvc.perform(get("/api/v1/public/tables/not-a-token")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/tables/" + "0".repeat(64))).andExpect(status().isNotFound());

        postJson(s.bearer(), "/api/v1/restaurant/tables/" + s.tableId() + "/qr/regenerate", "").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/tables/" + s.token())).andExpect(status().isNotFound());

        String fresh = read(mvc.perform(get("/api/v1/restaurant/branches/" + s.branchId() + "/tables").header("Authorization", s.bearer())), "$[0].qrToken");
        mvc.perform(get("/api/v1/public/tables/" + fresh)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/restaurant/tables/" + s.tableId())
                        .header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":4,\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/tables/" + fresh)).andExpect(status().isNotFound());
    }

    @Test
    void anotherRestaurantCannotSeeOrDecideMyGuestOrders() throws Exception {
        Shop a = shop();
        Shop b = shop();
        String order = read(guestOrder(a, 1), "$.id");
        postJson(b.bearer(), "/api/v1/restaurant/guest-orders/" + order + "/accept", "").andExpect(status().isNotFound());
        postJson(b.bearer(), "/api/v1/restaurant/guest-orders/" + order + "/reject", "").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/restaurant/branches/" + a.branchId() + "/guest-orders").header("Authorization", b.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/tables/" + b.token() + "/orders/" + order)).andExpect(status().isNotFound());
        postJson(null, "/api/v1/public/tables/" + b.token() + "/orders",
                "{\"guests\":1,\"lines\":[{\"menuItemId\":\"%s\",\"quantity\":1}]}".formatted(a.dish()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));
    }
}
