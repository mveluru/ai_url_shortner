package com.urlshortener.common.web;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TomcatConfig {

    /** Installs {@link JsonErrorReportValve} as the host's error reporter (container-level errors get the standard body). */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonContainerErrors() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
            }
        });
    }
}
