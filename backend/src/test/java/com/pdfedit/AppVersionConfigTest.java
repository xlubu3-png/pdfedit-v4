package com.pdfedit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** The version on screen must read exactly as written in application.yml, trailing zero included. */
class AppVersionConfigTest {

    @Test
    void theVersionKeepsItsThreeDecimalsWhenSpringReadsTheYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        String version = yaml.getObject().getProperty("app.version");

        assertThat(version).matches("\\d+\\.\\d{3}");
    }
}
