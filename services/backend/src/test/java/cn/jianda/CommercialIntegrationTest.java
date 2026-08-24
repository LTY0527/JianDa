package cn.jianda;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-commercial-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class CommercialIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    long productId;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM refund_request");
        jdbc.update("DELETE FROM payment_event");
        jdbc.update("DELETE FROM payment_order");
        jdbc.update("DELETE FROM service_order");
        jdbc.update("DELETE FROM service_product");
        jdbc.update("DELETE FROM service_provider");
        jdbc.update("INSERT INTO service_provider(name,verification_status,contact_phone,refund_policy,status) "
                + "VALUES ('已核验测试服务机构','VERIFIED','021-55550000','服务开始前可申请取消','ACTIVE')");
        Long providerId = jdbc.queryForObject("SELECT MAX(id) FROM service_provider", Long.class);
        jdbc.update("INSERT INTO service_product(provider_id,name,category,description,region_code,service_area,price_cents,status) "
                + "VALUES (?,'陪诊预约测试服务','陪诊','陪同挂号和取报告','310113102','大场镇',12000,'ACTIVE')", providerId);
        productId = jdbc.queryForObject("SELECT MAX(id) FROM service_product", Long.class);
    }

    @Test
    void verifiedRegionalServiceCreatesPendingOrderButNeverFakesPayment() throws Exception {
        String token = residentToken();
        mvc.perform(get("/api/public/commercial/services").param("regionCode", "310113102"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].provider_name").value("已核验测试服务机构"));
        mvc.perform(get("/api/public/commercial/services").param("regionCode", "310113109"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/public/commercial/payment-capabilities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.provider").value("UNCONFIGURED"));
        String created = mvc.perform(post("/api/public/commercial/orders").header("X-Resident-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"quantity\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.amountCents").value(24000))
                .andExpect(jsonPath("$.data.payment.available").value(false))
                .andReturn().getResponse().getContentAsString();
        String orderNo = objectMapper.readTree(created).path("data").path("orderNo").asText();
        Long orderId = jdbc.queryForObject("SELECT id FROM service_order WHERE order_no=?", Long.class, orderNo);
        mvc.perform(post("/api/public/commercial/orders/{id}/cancel", orderId).header("X-Resident-Token", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/commercial/orders").header("X-Resident-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("CANCELLED"));
        mvc.perform(post("/api/public/commercial/orders/{id}/refund", orderId).header("X-Resident-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"测试退款申请原因\"}"))
                .andExpect(status().isConflict());
    }

    private String residentToken() throws Exception {
        String body = mvc.perform(post("/api/public/resident/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo_chen\",\"password\":\"Resident@123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
