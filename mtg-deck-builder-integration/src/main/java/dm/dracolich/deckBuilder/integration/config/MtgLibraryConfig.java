package dm.dracolich.deckBuilder.integration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class MtgLibraryConfig {

    @Value("${dracolich.mtg-library.api.base-url}")
    private String baseUrl;

    // How long to wait for a connection, and for an answer once connected. A library that hangs
    // would otherwise keep the caller's request hanging with it.
    @Value("${dracolich.mtg-library.api.connect-timeout:PT3S}")
    private Duration connectTimeout;

    @Value("${dracolich.mtg-library.api.response-timeout:PT10S}")
    private Duration responseTimeout;

    @Bean
    public WebClient mtgLibraryWebClient() {
        var mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient(connectTimeout, responseTimeout)))
                .defaultHeader("Accept", "application/json")
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024);
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(mapper));
                })
                .build();
    }

    public static HttpClient httpClient(Duration connectTimeout, Duration responseTimeout) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout);
    }
}
