package cn.jianda.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ConfiguredWebSearchProviderTest {
    @Test
    void disabledProviderDoesNotAttemptNetworkAccess() {
        WebSearchProvider provider = new ConfiguredWebSearchProvider(
                new ObjectMapper(), "disabled", "", "https://api.tavily.com/search");

        assertEquals("disabled", provider.status().state());
        assertFalse(provider.status().ready());
        assertThrows(IllegalStateException.class, () -> provider.search("上海社区活动", 3));
    }

    @Test
    void tavilyRequiresCredentialAndEndpointRequiresHttps() {
        WebSearchProvider provider = new ConfiguredWebSearchProvider(
                new ObjectMapper(), "tavily", "", "https://api.tavily.com/search");

        assertEquals("degraded", provider.status().state());
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredWebSearchProvider(
                new ObjectMapper(), "tavily", "secret", "http://example.com/search"));
    }
}
