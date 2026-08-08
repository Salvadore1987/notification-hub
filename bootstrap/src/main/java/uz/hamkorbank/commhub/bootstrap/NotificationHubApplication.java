package uz.hamkorbank.commhub.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the Notification Hub.
 *
 * <p>Component scanning is anchored at {@code uz.hamkorbank.commhub} so that adapter and application
 * beans are picked up while the domain module stays framework-free (AR-02).
 *
 * <p>Scheduling is on for the background jobs the storage layer needs: partition maintenance and
 * retention (DB-02, DB-03).
 */
@SpringBootApplication
@ComponentScan("uz.hamkorbank.commhub")
@ConfigurationPropertiesScan("uz.hamkorbank.commhub")
@EnableScheduling
public class NotificationHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationHubApplication.class, args);
    }
}
