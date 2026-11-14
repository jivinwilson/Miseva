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
class InventoryTests {

    static final String DOMAIN = "@stocktest.local";

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

    record Org(String bearer, String branchId) {
    }

    private ResultActions send(String bearer, String method, String path, String json) throws Exception {
        var req = ("PUT".equals(method) ? put(path) : post(path)).contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(bearer == null ? req : req.header("Authorization", bearer));
    }

    private static String read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path).toString();
    }

    private Org register() throws Exception {
        ResultActions reg = send(null, "POST", "/api/v1/businesses/register", """
                {"businessName":"Stocktest %s","industry":"restaurant","countryCode":"IN","currency":"INR",
                 "timezone":"Asia/Kolkata","ownerFirstName":"Asha","ownerLastName":"Rao","email":"u%s%s","password":"correct-horse-battery"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID().toString().substring(0, 8), DOMAIN))
                .andExpect(status().isCreated());
        return new Org("Bearer " + read(reg, "$.tokens.accessToken"), read(reg, "$.headOfficeBranchId"));
    }

    private String ingredient(Org o, String name, String unit, String threshold) throws Exception {
        return read(send(o.bearer(), "POST", "/api/v1/restaurant/ingredients",
                "{\"name\":\"%s\",\"unit\":\"%s\",\"lowStockThreshold\":%s}".formatted(name, unit, threshold))
                .andExpect(status().isCreated()), "$.id");
    }

    private ResultActions move(Org o, String ingredientId, String reason, String qty) throws Exception {
        return send(o.bearer(), "POST", "/api/v1/restaurant/branches/" + o.branchId() + "/stock/movements",
                "{\"ingredientId\":\"%s\",\"reason\":\"%s\",\"quantity\":%s}".formatted(ingredientId, reason, qty));
    }

    private String dish(Org o, String name) throws Exception {
        String cat = read(send(o.bearer(), "POST", "/api/v1/restaurant/menu/categories", "{\"name\":\"Mains\",\"sortOrder\":0}"), "$.id");
        return read(send(o.bearer(), "POST", "/api/v1/restaurant/menu/items",
                "{\"categoryId\":\"%s\",\"name\":\"%s\",\"priceMinor\":20000,\"taxRateBp\":0,\"sortOrder\":0}".formatted(cat, name)), "$.id");
    }

    private void sell(Org o, String dishId, int qty) throws Exception {
        String table = read(send(o.bearer(), "POST", "/api/v1/restaurant/branches/" + o.branchId() + "/tables",
                "{\"code\":\"T%s\",\"seats\":4}".formatted(UUID.randomUUID().toString().substring(0, 4))), "$.id");
        String session = read(send(o.bearer(), "POST", "/api/v1/restaurant/tables/" + table + "/sessions", "{\"guests\":2}"), "$.session.id");
        send(o.bearer(), "POST", "/api/v1/restaurant/sessions/" + session + "/lines",
                "{\"lines\":[{\"menuItemId\":\"%s\",\"quantity\":%d}]}".formatted(dishId, qty)).andExpect(status().isOk());
        ResultActions bill = send(o.bearer(), "POST", "/api/v1/restaurant/sessions/" + session + "/bill", "").andExpect(status().isOk());
        send(o.bearer(), "POST", "/api/v1/restaurant/bills/" + read(bill, "$.bill.bill.id") + "/payments",
                "{\"method\":\"cash\",\"amountMinor\":%s}".formatted(read(bill, "$.bill.bill.totalMinor"))).andExpect(status().isOk());
    }

    private ResultActions stock(Org o) throws Exception {
        return mvc.perform(get("/api/v1/restaurant/branches/" + o.branchId() + "/stock").header("Authorization", o.bearer()));
    }

    @Test
    void payingABillConsumesRecipeIngredientsFromStock() throws Exception {
        Org o = register();
        String rice = ingredient(o, "Basmati rice", "g", "1000");
        String chicken = ingredient(o, "Chicken", "g", "500");
        String biryani = dish(o, "Chicken Biryani");

        send(o.bearer(), "PUT", "/api/v1/restaurant/menu/items/" + biryani + "/recipe",
                "{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":180},{\"ingredientId\":\"%s\",\"quantity\":150.5}]}".formatted(rice, chicken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        move(o, rice, "purchase", "5000").andExpect(status().isOk());
        move(o, chicken, "purchase", "2000").andExpect(status().isOk());
        move(o, chicken, "waste", "100").andExpect(status().isOk());
        move(o, rice, "adjustment", "-50").andExpect(status().isOk());

        sell(o, biryani, 3);

        stock(o).andExpect(status().isOk())
                .andExpect(jsonPath("$.levels[?(@.name=='Basmati rice')].onHand").value(4410.0))
                .andExpect(jsonPath("$.levels[?(@.name=='Chicken')].onHand").value(1448.5))
                .andExpect(jsonPath("$.levels[?(@.name=='Basmati rice')].low").value(false))
                .andExpect(jsonPath("$.recent[0].reason").value("consumption"));

        sell(o, biryani, 5);
        stock(o).andExpect(jsonPath("$.levels[?(@.name=='Chicken')].onHand").value(696.0));
        sell(o, biryani, 2);
        stock(o).andExpect(jsonPath("$.levels[?(@.name=='Chicken')].onHand").value(395.0))
                .andExpect(jsonPath("$.levels[?(@.name=='Chicken')].low").value(true));
    }

    @Test
    void validationAndRules() throws Exception {
        Org o = register();
        String salt = ingredient(o, "Salt", "g", "0");
        send(o.bearer(), "POST", "/api/v1/restaurant/ingredients", "{\"name\":\"Salt\",\"unit\":\"g\",\"lowStockThreshold\":0}")
                .andExpect(status().isConflict());
        send(o.bearer(), "POST", "/api/v1/restaurant/ingredients", "{\"name\":\"Oil\",\"unit\":\"litre\",\"lowStockThreshold\":0}")
                .andExpect(status().isBadRequest());
        move(o, salt, "adjustment", "0").andExpect(status().isBadRequest());
        move(o, salt, "purchase", "-5").andExpect(status().isOk());
        stock(o).andExpect(jsonPath("$.levels[0].onHand").value(5.0));

        String dish = dish(o, "Soup");
        send(o.bearer(), "PUT", "/api/v1/restaurant/menu/items/" + dish + "/recipe",
                "{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":1},{\"ingredientId\":\"%s\",\"quantity\":2}]}".formatted(salt, salt))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_INGREDIENT"));
        send(o.bearer(), "PUT", "/api/v1/restaurant/menu/items/" + dish + "/recipe",
                "{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":0}]}".formatted(salt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staffCannotSeeStockAndOtherRestaurantsCannotTouchIt() throws Exception {
        Org a = register();
        Org b = register();
        String rice = ingredient(a, "Rice", "g", "0");
        String dish = dish(a, "Pulao");

        stock(new Org(b.bearer(), a.branchId())).andExpect(status().isNotFound());
        move(new Org(b.bearer(), b.branchId()), rice, "purchase", "10")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INGREDIENT_NOT_FOUND"));
        send(b.bearer(), "PUT", "/api/v1/restaurant/menu/items/" + dish + "/recipe", "{\"lines\":[]}").andExpect(status().isNotFound());
        String bDish = dish(b, "Pulao");
        send(b.bearer(), "PUT", "/api/v1/restaurant/menu/items/" + bDish + "/recipe",
                "{\"lines\":[{\"ingredientId\":\"%s\",\"quantity\":1}]}".formatted(rice))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INGREDIENT_NOT_FOUND"));

        String roles = mvc.perform(get("/api/v1/roles/assignable").header("Authorization", a.bearer())).andReturn().getResponse().getContentAsString();
        String staffRole = JsonPath.<List<String>>read(roles, "$[?(@.code=='staff')].id").get(0);
        String invite = read(send(a.bearer(), "POST", "/api/v1/employees/invitations",
                "{\"firstName\":\"Dev\",\"lastName\":\"Nair\",\"email\":\"w%s%s\",\"roleIds\":[\"%s\"],\"allBranches\":true}"
                        .formatted(UUID.randomUUID().toString().substring(0, 8), DOMAIN, staffRole)), "$.inviteUrl");
        String staff = "Bearer " + read(send(null, "POST", "/api/v1/invitations/accept",
                "{\"token\":\"%s\",\"password\":\"correct-horse-battery\"}".formatted(invite.substring(invite.indexOf("token=") + 6))), "$.accessToken");
        stock(new Org(staff, a.branchId())).andExpect(status().isForbidden());
        move(new Org(staff, a.branchId()), rice, "purchase", "10").andExpect(status().isForbidden());
    }

    @Test
    void stockMovementsCannotBeEditedOrDeletedByTheApp() {
        JdbcTemplate app = new JdbcTemplate(appDataSource);
        assertThatThrownBy(() -> app.update("UPDATE stock_movements SET quantity = 1")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM stock_movements")).rootCause().hasMessageContaining("permission denied");
    }
}
