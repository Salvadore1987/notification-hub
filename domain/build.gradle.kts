// AR-02: домен не имеет compile-зависимостей на Spring, JPA, Kafka, Jackson.
// Допустимы только JDK, java.time и собственные value objects.
// Правило контролируется ArchUnit-тестами (AR-03, QA-02).

dependencies {
    // Намеренно пусто: никаких runtime/implementation-зависимостей у доменного модуля нет.
}
