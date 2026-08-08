package uz.hamkorbank.commhub.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Notification Hub.
 *
 * <p>Component scanning is anchored at {@code uz.hamkorbank.commhub} so that adapter and application
 * beans are picked up while the domain module stays framework-free (AR-02).
 */
@SpringBootApplication
@ComponentScan("uz.hamkorbank.commhub")
public class NotificationHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationHubApplication.class, args);
    }
}
