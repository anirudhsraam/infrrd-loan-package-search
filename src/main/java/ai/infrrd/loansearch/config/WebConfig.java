package ai.infrrd.loansearch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String dataLocation;

    public WebConfig(@Value("${app.project-root:.}") String projectRoot,
                     @Value("${app.data-dir:data}") String dataDir) {
        this.dataLocation = Path.of(projectRoot).resolve(dataDir).resolve("pdf").toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Spring's ResourceHttpRequestHandler supports byte ranges, enabling native PDF page navigation.
        registry.addResourceHandler("/package-data/**").addResourceLocations(dataLocation).setCachePeriod(0);
    }
}
