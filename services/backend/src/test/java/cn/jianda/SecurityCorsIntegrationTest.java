package cn.jianda;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import cn.jianda.security.SecurityConfig;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-cors-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.cors.allowed-origins=http://127.0.0.1:5174,http://10.10.10.10:5174"
})
@AutoConfigureMockMvc
class SecurityCorsIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void configuredLanOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/public/items")
                        .header("Origin", "http://10.10.10.10:5174")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Anonymous-User"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://10.10.10.10:5174"));
    }

    @Test
    void unconfiguredOriginIsRejectedWithoutWildcard() throws Exception {
        mvc.perform(options("/api/public/items")
                        .header("Origin", "http://10.10.10.11:5174")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void wildcardConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(java.util.List.of("*")));
    }
}
