# Hexagonal Architecture + DDD: Code Style & Guidelines

> Практическое руководство по стилю кода и конвенциям для Spring Boot микросервисов
> на базе гексагональной архитектуры с Domain-Driven Design.
>
> **Java:** 17+ | **Spring Boot:** 2.6+ / 3.x | **Messaging:** Apache Kafka + Avro
> **Сборка:** Maven multi-module

---

## Содержание

1. [Архитектурные принципы](#1-архитектурные-принципы)
2. [Структура модулей](#2-структура-модулей)
3. [Domain Layer — стиль кода](#3-domain-layer--стиль-кода)
4. [Application Layer — стиль кода](#4-application-layer--стиль-кода)
5. [Infrastructure Layer — стиль кода](#5-infrastructure-layer--стиль-кода)
6. [Bootstrap Module — стиль кода](#6-bootstrap-module--стиль-кода)
7. [Именование](#7-именование)
8. [Маппинг между слоями](#8-маппинг-между-слоями)
9. [Outbox Pattern](#9-outbox-pattern)
10. [Saga Pattern](#10-saga-pattern)
11. [Обработка ошибок](#11-обработка-ошибок)
12. [Тестирование](#12-тестирование)
13. [Чеклисты](#13-чеклисты)

---

## 1. Архитектурные принципы

### 1.1. Направление зависимостей

Зависимости всегда направлены **внутрь** — от инфраструктуры к домену, никогда наоборот.

```
bootstrap → infrastructure → application → domain
```

| Модуль | Зависит от | НЕ зависит от |
|---|---|---|
| `domain` | Ничего (чистая Java) | Spring, JPA, Kafka, Avro — ничего |
| `application` | `domain` | Spring Data, Kafka, Avro |
| `infrastructure` | `application` (и транзитивно `domain`) | — |
| `bootstrap` | Все модули | — |

### 1.2. Золотое правило

> **Domain — это чистая Java. Ни одной Spring-аннотации.**
>
> Никаких `@Component`, `@Service`, `@Autowired`, `@Transactional` в модуле domain.
> Domain Service регистрируется как `@Bean` в `BeanConfiguration` модуля bootstrap.

### 1.3. Правило портов

> **Порты определяются в application. Адаптеры реализуют порты в infrastructure.**
>
> Порт — это интерфейс. Адаптер — это `@Component`, реализующий этот интерфейс.

---

## 2. Структура модулей

### 2.1. Стандартная структура сервиса (4 Maven-модуля)

```
{context-name}/
├── pom.xml                                        # Parent POM (module declarations)
│
├── domain/                                        # Ядро — чистая бизнес-логика (0 зависимостей)
│   ├── pom.xml                                    # Нет Spring-зависимостей
│   └── src/main/java/
│       └── com/company/{context}/domain/
│           ├── model/                             # Aggregates, Entities, Value Objects
│           │   ├── {Entity}.java                  # AggregateRoot / BaseEntity
│           │   ├── {Entity}Id.java                # BaseId<UUID>
│           │   └── ...
│           ├── event/                             # Domain Events
│           │   ├── {Entity}Event.java             # Абстрактный базовый event
│           │   ├── {Entity}CreatedEvent.java      # Конкретные events
│           │   └── ...
│           ├── exception/                         # Domain Exceptions
│           │   ├── {Context}DomainException.java
│           │   └── {Entity}NotFoundException.java
│           └── service/                           # Domain Services
│               ├── {Entity}DomainService.java     # Интерфейс
│               └── {Entity}DomainServiceImpl.java # Чистая Java, без Spring
│
├── application/                                   # Application Layer — оркестрация use cases
│   ├── pom.xml                                    # Зависит от domain
│   └── src/main/java/
│       └── com/company/{context}/application/
│           ├── port/
│           │   ├── input/                         # Input Ports (Use Case interfaces)
│           │   │   ├── {Entity}ApplicationService.java
│           │   │   └── {Event}MessageListener.java
│           │   └── output/                        # Output Ports (Repository, Gateway interfaces)
│           │       ├── {Entity}Repository.java
│           │       └── {Event}MessagePublisher.java
│           ├── usecase/                           # Use Case implementations (Command/Query Handlers)
│           │   ├── {Entity}CreateCommandHandler.java
│           │   └── {Entity}TrackQueryHandler.java
│           ├── dto/                               # Command & Query DTOs
│           │   ├── {Action}{Entity}Command.java
│           │   ├── Track{Entity}Query.java
│           │   └── {Action}{Entity}Response.java
│           ├── mapper/                            # Domain <-> DTO mappers
│           │   └── {Entity}DataMapper.java
│           ├── outbox/                            # Outbox components (если нужен)
│           │   ├── model/
│           │   │   └── {Entity}{Event}OutboxMessage.java
│           │   └── scheduler/
│           │       ├── {Event}OutboxHelper.java
│           │       ├── {Event}OutboxScheduler.java
│           │       └── {Event}OutboxCleanerScheduler.java
│           └── {Entity}ApplicationServiceImpl.java
│
├── infrastructure/                                # Adapters — внешний мир
│   ├── pom.xml                                    # Зависит от application (и транзитивно от domain)
│   └── src/main/java/
│       └── com/company/{context}/infrastructure/
│           ├── adapter/
│           │   ├── input/
│           │   │   ├── rest/                      # REST Controllers (Primary/Driving Adapters)
│           │   │   │   └── {Entity}Controller.java
│           │   │   └── event/                     # Event Listeners — Kafka Consumers
│           │   │       └── {Event}KafkaListener.java
│           │   └── output/
│           │       ├── persistence/               # JPA Repositories (Secondary/Driven Adapters)
│           │       │   ├── entity/                # JPA Entities (@Entity)
│           │       │   │   └── {Entity}Entity.java
│           │       │   ├── repository/            # Spring Data JPA Repositories
│           │       │   │   └── {Entity}JpaRepository.java
│           │       │   ├── adapter/               # Output port implementations
│           │       │   │   └── {Entity}RepositoryImpl.java
│           │       │   └── mapper/                # Domain <-> JPA Entity mapper
│           │       │       └── {Entity}DataAccessMapper.java
│           │       ├── messaging/                 # Kafka Producers
│           │       │   ├── publisher/
│           │       │   │   └── {Entity}{Event}KafkaPublisher.java
│           │       │   └── mapper/
│           │       │       └── {Entity}MessagingDataMapper.java
│           │       ├── external/                  # External API clients (AI, Email, etc.)
│           │       └── storage/                   # File/Object Storage adapters
│           └── config/                            # Spring Configuration
│               └── {Feature}Config.java
│
└── bootstrap/                                     # Сборка и запуск приложения (без бизнес-логики)
    ├── pom.xml                                    # Зависит от всех модулей
    └── src/main/java/
        └── com/company/{context}/bootstrap/
            ├── {Context}Application.java          # @SpringBootApplication — точка входа
            ├── config/
            │   ├── BeanConfiguration.java         # @Bean для Domain Services
            │   ├── SecurityConfig.java            # Spring Security
            │   ├── KafkaConfig.java               # Kafka
            │   ├── JacksonConfig.java             # JSON
            │   ├── CorsConfig.java                # CORS
            │   ├── OpenApiConfig.java             # Swagger/OpenAPI
            │   ├── FlywayConfig.java              # Migrations
            │   └── ObservabilityConfig.java       # Metrics, tracing
            └── resources/
                ├── application.yml                # Основной конфиг (profiles: dev, staging, prod)
                ├── application-dev.yml
                ├── application-prod.yml
                └── logback-spring.xml
```

### 2.2. Зависимости между модулями

```
bootstrap → infrastructure, application, domain
infrastructure → application → domain
```

| Модуль | Зависит от | НЕ зависит от |
|---|---|---|
| `domain` | Ничего (чистая Java) | Spring, JPA, Kafka, Avro |
| `application` | `domain` | Spring Data, Kafka, Avro, JPA |
| `infrastructure` | `application` (и транзитивно `domain`) | — |
| `bootstrap` | Все модули | — |

### 2.3. Ключевое отличие от 6-модульной схемы

> В 4-модульной структуре REST-контроллеры, JPA persistence и Kafka messaging — это **адаптеры
> внутри одного модуля `infrastructure`**, а не отдельные Maven-модули. Это упрощает сборку
> и управление зависимостями, сохраняя при этом чёткое разделение по пакетам.

---

## 3. Domain Layer — стиль кода

### 3.1. Базовые классы (common-domain)

Все сущности, агрегаты и идентификаторы наследуют от базовых классов:

```java
// ✅ Базовая сущность — equals/hashCode по id
public abstract class BaseEntity<ID> {
    private ID id;

    public ID getId() { return id; }
    public void setId(ID id) { this.id = id; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseEntity<?> that = (BaseEntity<?>) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}

// ✅ Корень агрегата
public abstract class AggregateRoot<ID> extends BaseEntity<ID> {
}

// ✅ Типизированный идентификатор
public abstract class BaseId<T> {
    private final T value;

    protected BaseId(T value) { this.value = value; }
    public T getValue() { return value; }

    @Override
    public boolean equals(Object o) { /* by value */ }
    @Override
    public int hashCode() { return value.hashCode(); }
}
```

### 3.2. Aggregate Root

```java
// ✅ Правильно: Builder, бизнес-логика внутри, нет Spring-аннотаций
@Getter
@Builder
public class Order extends AggregateRoot<OrderId> {
    private final CustomerId customerId;
    private final RestaurantId restaurantId;
    private final StreetAddress deliveryAddress;
    private final Money price;
    private final List<OrderItem> items;

    // Мутабельные поля — только через бизнес-методы
    private TrackingId trackingId;
    private OrderStatus orderStatus;
    private List<String> failureMessages;

    // ✅ Бизнес-логика инкапсулирована в агрегате
    public void validateOrder() {
        validateInitialOrder();
        validateTotalPrice();
        validateItemsPrice();
    }

    public void initializeOrder() {
        setId(new OrderId(UUID.randomUUID()));
        trackingId = new TrackingId(UUID.randomUUID());
        orderStatus = OrderStatus.PENDING;
        initializeOrderItems();
    }

    public void pay() {
        if (orderStatus != OrderStatus.PENDING) {
            throw new OrderDomainException("Order is not in correct state for pay operation");
        }
        orderStatus = OrderStatus.PAID;
    }

    public void cancel(List<String> failureMessages) {
        if (!(orderStatus == OrderStatus.PENDING || orderStatus == OrderStatus.PAID)) {
            throw new OrderDomainException("Order is not in correct state for cancel operation");
        }
        orderStatus = OrderStatus.CANCELLED;
        updateFailureMessages(failureMessages);
    }

    // ✅ Приватные валидации
    private void validateTotalPrice() {
        if (price == null || !price.isGreaterThanZero()) {
            throw new OrderDomainException("Total price must be greater than zero!");
        }
    }

    private void validateItemsPrice() {
        Money orderItemsTotal = items.stream()
                .map(item -> {
                    validateItemPrice(item);
                    return item.getSubTotal();
                })
                .reduce(Money.ZERO, Money::add);

        if (!price.equals(orderItemsTotal)) {
            throw new OrderDomainException("Total price does not equal Order items total!");
        }
    }
}
```

**Правила для агрегатов:**

| # | Правило |
|---|---|
| 1 | Наследует `AggregateRoot<{Name}Id>` |
| 2 | `@Getter @Builder` — нет публичных сеттеров |
| 3 | Вся бизнес-логика — методы агрегата (`validate()`, `pay()`, `cancel()`) |
| 4 | Guard clauses в бизнес-методах: проверка текущего состояния перед переходом |
| 5 | Доступ к дочерним сущностям — только через корень агрегата |
| 6 | Не возвращает domain events напрямую — это делает Domain Service |
| 7 | Исключения — domain-specific (наследуют `DomainException`) |

### 3.3. Value Objects

```java
// ✅ ID Value Object — всегда наследует BaseId<UUID>
public class OrderId extends BaseId<UUID> {
    public OrderId(UUID value) {
        super(value);
    }
}

// ✅ Non-ID Value Object — иммутабельный, с поведением
@Getter
public class Money {
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    public Money(BigDecimal amount) {
        this.amount = amount;
    }

    public boolean isGreaterThanZero() {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isGreaterThan(Money money) {
        return amount != null && amount.compareTo(money.getAmount()) > 0;
    }

    public Money add(Money money) {
        return new Money(setScale(amount.add(money.getAmount())));
    }

    public Money subtract(Money money) {
        return new Money(setScale(amount.subtract(money.getAmount())));
    }

    public Money multiply(int multiplier) {
        return new Money(setScale(amount.multiply(new BigDecimal(multiplier))));
    }

    // equals/hashCode by amount
}

// ✅ Составной Value Object
@Getter
@Builder
@AllArgsConstructor
public class StreetAddress {
    private final UUID id;
    private final String street;
    private final String postalCode;
    private final String city;

    // equals/hashCode by all fields except id
}
```

**Правила для Value Objects:**

| # | Правило |
|---|---|
| 1 | ID-типы: один класс на сущность, наследует `BaseId<UUID>` |
| 2 | Non-ID: immutable (final поля, нет сеттеров) |
| 3 | `Money` — единственный способ работать с денежными суммами |
| 4 | Shared VOs (`Money`, `OrderStatus`, `PaymentStatus`) — в `common-domain/valueobject/` |
| 5 | Service-specific VOs — в `domain/model/` |

### 3.4. Domain Events

```java
// ✅ Базовый класс события агрегата
@Getter
@AllArgsConstructor
public abstract class OrderEvent implements DomainEvent<Order> {
    private final Order order;
    private final ZonedDateTime createdAt;
}

// ✅ Конкретные события
public class OrderCreatedEvent extends OrderEvent {
    public OrderCreatedEvent(Order order, ZonedDateTime createdAt) {
        super(order, createdAt);
    }
}

public class OrderPaidEvent extends OrderEvent {
    public OrderPaidEvent(Order order, ZonedDateTime createdAt) {
        super(order, createdAt);
    }
}

public class OrderCancelledEvent extends OrderEvent {
    private final List<String> failureMessages;

    public OrderCancelledEvent(Order order, ZonedDateTime createdAt,
                                List<String> failureMessages) {
        super(order, createdAt);
        this.failureMessages = failureMessages;
    }
}
```

**Правила для Domain Events:**

| # | Правило |
|---|---|
| 1 | Реализуют `DomainEvent<T>` (маркерный интерфейс) |
| 2 | Абстрактный базовый класс на агрегат (`OrderEvent`, `PaymentEvent`) |
| 3 | Содержат ссылку на агрегат + `createdAt` |
| 4 | Создаются **Domain Service**, не агрегатом |
| 5 | Immutable — final поля |

### 3.5. Domain Service

```java
// ✅ Интерфейс — в domain/service/
public interface OrderDomainService {
    OrderCreatedEvent validateAndInitiateOrder(Order order, Restaurant restaurant);
    OrderPaidEvent payOrder(Order order);
    void approveOrder(Order order);
    OrderCancelledEvent cancelOrderPayment(Order order, List<String> failureMessages);
}

// ✅ Реализация — plain Java, нет Spring
@Slf4j
public class OrderDomainServiceImpl implements OrderDomainService {

    @Override
    public OrderCreatedEvent validateAndInitiateOrder(Order order, Restaurant restaurant) {
        validateRestaurant(restaurant);
        setOrderProductInformation(order, restaurant);
        order.validateOrder();
        order.initializeOrder();
        log.info("Order with id: {} is initiated", order.getId().getValue());
        return new OrderCreatedEvent(order, ZonedDateTime.now(ZoneId.of("UTC")));
    }

    @Override
    public OrderPaidEvent payOrder(Order order) {
        order.pay();
        log.info("Order with id: {} is paid", order.getId().getValue());
        return new OrderPaidEvent(order, ZonedDateTime.now(ZoneId.of("UTC")));
    }

    @Override
    public OrderCancelledEvent cancelOrderPayment(Order order, List<String> failureMessages) {
        order.cancel(failureMessages);
        log.info("Order payment is cancelling for order id: {}", order.getId().getValue());
        return new OrderCancelledEvent(order, ZonedDateTime.now(ZoneId.of("UTC")));
    }
}
```

**Правила для Domain Service:**

| # | Правило |
|---|---|
| 1 | Интерфейс + реализация в `domain/service/` |
| 2 | **Чистая Java** — нет `@Service`, `@Component`, `@Transactional` |
| 3 | Оркестрирует поведение агрегатов |
| 4 | Возвращает domain events |
| 5 | Регистрируется как `@Bean` в `BeanConfiguration` |
| 6 | Можно использовать `@Slf4j` (Lombok + SLF4J — не Spring) |
| 7 | **Вся работа с доменной моделью — только через Domain Service** (см. ниже) |

### 3.6. Правило: работа с доменной моделью только через Domain Service

> **Ни один слой выше domain не должен напрямую вызывать бизнес-методы агрегатов.**
> Вся работа с доменной моделью (создание, валидация, переходы состояний) выполняется
> исключительно через Domain Service.

**Почему:**
- Domain Service — единственная точка входа в доменную логику. Это гарантирует, что все инварианты проверены, все побочные эффекты (domain events) созданы, и бизнес-операция выполнена атомарно.
- Если Application Layer (Command Handler) начнёт вызывать `order.pay()` или `order.cancel()` напрямую, минуя Domain Service, то domain events не будут созданы, и межсервисная коммуникация сломается.
- Domain Service инкапсулирует сценарии, которые требуют координации нескольких агрегатов или внешних проверок (например, валидация ресторана перед созданием заказа).

```java
// ❌ НЕПРАВИЛЬНО — Command Handler вызывает бизнес-методы агрегата напрямую
@Component
public class OrderCreateCommandHandler {
    @Transactional
    public CreateOrderResponse createOrder(CreateOrderCommand cmd) {
        Order order = orderDataMapper.createOrderCommandToOrder(cmd);
        order.validateOrder();       // ❌ Прямой вызов агрегата
        order.initializeOrder();     // ❌ Прямой вызов агрегата
        orderRepository.save(order);
        // ❌ Domain event не создан — payment service не узнает о новом заказе
        return orderDataMapper.orderToCreateOrderResponse(order, "Order created");
    }
}

// ✅ ПРАВИЛЬНО — Command Handler делегирует в Domain Service
@Component
public class OrderCreateCommandHandler {
    private final OrderDomainService orderDomainService;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderCommand cmd) {
        Restaurant restaurant = checkRestaurant(cmd);
        Order order = orderDataMapper.createOrderCommandToOrder(cmd);

        // ✅ Domain Service валидирует, инициализирует и возвращает event
        OrderCreatedEvent event = orderDomainService.validateAndInitiateOrder(order, restaurant);

        orderRepository.save(order);
        paymentOutboxHelper.savePaymentOutboxMessage(/* ... из event ... */);
        return orderDataMapper.orderToCreateOrderResponse(order, "Order created");
    }
}
```

**Граница ответственности:**

| Слой | Что делает | Что НЕ делает |
|---|---|---|
| **Command Handler** (application) | Загружает данные, вызывает Domain Service, сохраняет результат, пишет в outbox | Не вызывает бизнес-методы агрегатов напрямую |
| **Domain Service** (domain) | Валидирует, вызывает бизнес-методы агрегатов, координирует агрегаты, создаёт domain events | Не обращается к репозиториям, не знает о persistence |
| **Aggregate** (domain) | Содержит бизнес-логику, guard clauses, переходы состояний | Не создаёт domain events, не знает о других агрегатах |

---

## 4. Application Layer — стиль кода

### 4.1. Input Ports

#### Service Ports (вызываются REST-контроллерами)

```java
// ✅ Порт — интерфейс в ports/input/service/
public interface OrderApplicationService {
    CreateOrderResponse createOrder(CreateOrderCommand command);
    TrackOrderResponse trackOrder(TrackOrderQuery query);
}
```

#### Message Listener Ports (вызываются Kafka-консьюмерами)

```java
// ✅ Порт — интерфейс в ports/input/message/listener/
public interface PaymentResponseMessageListener {
    void paymentCompleted(PaymentResponse paymentResponse);
    void paymentCancelled(PaymentResponse paymentResponse);
}
```

### 4.2. Output Ports

#### Repository Ports

```java
// ✅ Порт — интерфейс в ports/output/repository/
public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(OrderId orderId);
    Optional<Order> findByTrackingId(TrackingId trackingId);
}
```

#### Message Publisher Ports

```java
// ✅ Порт с callback для outbox
public interface PaymentRequestMessagePublisher {
    void publish(OrderPaymentOutboxMessage orderPaymentOutboxMessage,
                 BiConsumer<OrderPaymentOutboxMessage, OutboxStatus> outboxCallback);
}
```

### 4.3. DTOs

```java
// ✅ Command DTO — @Builder @Getter, валидация через @NotNull
@Getter
@Builder
@AllArgsConstructor
public class CreateOrderCommand {
    @NotNull
    private final UUID customerId;
    @NotNull
    private final UUID restaurantId;
    @NotNull
    private final BigDecimal price;
    @NotNull
    private final List<OrderItem> items;
    @NotNull
    private final OrderAddress address;
}

// ✅ Response DTO
@Getter
@Builder
@AllArgsConstructor
public class CreateOrderResponse {
    private final UUID orderTrackingId;
    private final OrderStatus orderStatus;
    private final String message;
}

// ✅ Query DTO
@Getter
@Builder
@AllArgsConstructor
public class TrackOrderQuery {
    @NotNull
    private final UUID orderTrackingId;
}

// ✅ Inter-service message DTO
@Getter
@Builder
@AllArgsConstructor
public class PaymentResponse {
    private String id;
    private String sagaId;
    private String orderId;
    private String paymentId;
    private BigDecimal price;
    private ZonedDateTime createdAt;
    private PaymentStatus paymentStatus;
    private List<String> failureMessages;
}
```

**Правила для DTO:**

| # | Правило |
|---|---|
| 1 | `@Getter @Builder @AllArgsConstructor` — нет сеттеров |
| 2 | Валидация: `@NotNull` на обязательных полях |
| 3 | Commands — именуются `{Action}{Entity}Command` |
| 4 | Queries — именуются `Track{Entity}Query` или `Get{Entity}Query` |
| 5 | Responses — именуются `{Action}{Entity}Response` |
| 6 | Messages — именуются `{Entity}Response` / `{Entity}Request` |
| 7 | Размещаются в `dto/{action}/` или `dto/message/` |

### 4.4. Application Service Implementation

```java
// ✅ Делегирует в command/query handlers
@Slf4j
@Validated
@Service
@RequiredArgsConstructor
public class OrderApplicationServiceImpl implements OrderApplicationService {
    private final OrderCreateCommandHandler orderCreateCommandHandler;
    private final OrderTrackCommandHandler orderTrackCommandHandler;

    @Override
    public CreateOrderResponse createOrder(CreateOrderCommand createOrderCommand) {
        return orderCreateCommandHandler.createOrder(createOrderCommand);
    }

    @Override
    public TrackOrderResponse trackOrder(TrackOrderQuery trackOrderQuery) {
        return orderTrackCommandHandler.trackOrder(trackOrderQuery);
    }
}
```

### 4.5. Command/Query Handlers

```java
// ✅ Handler — @Component с @Transactional
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateCommandHandler {
    private final OrderDomainService orderDomainService;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
    private final OrderDataMapper orderDataMapper;
    private final PaymentOutboxHelper paymentOutboxHelper;
    private final OrderSagaHelper orderSagaHelper;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderCommand createOrderCommand) {
        checkCustomer(createOrderCommand.getCustomerId());
        Restaurant restaurant = checkRestaurant(createOrderCommand);
        Order order = orderDataMapper.createOrderCommandToOrder(createOrderCommand);
        OrderCreatedEvent orderCreatedEvent =
                orderDomainService.validateAndInitiateOrder(order, restaurant);
        Order orderResult = saveOrder(order);
        log.info("Order is created with id: {}", orderResult.getId().getValue());

        // Запись в outbox в той же транзакции
        paymentOutboxHelper.savePaymentOutboxMessage(
                orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent),
                orderCreatedEvent.getOrder().getOrderStatus(),
                orderSagaHelper.orderStatusToSagaStatus(orderCreatedEvent.getOrder().getOrderStatus()),
                OutboxStatus.STARTED,
                UUID.randomUUID());

        log.info("Returning CreateOrderResponse with order id: {}", orderResult.getId().getValue());
        return orderDataMapper.orderToCreateOrderResponse(orderResult, "Order created successfully");
    }

    private void checkCustomer(UUID customerId) {
        customerRepository.findCustomer(customerId).orElseThrow(() ->
                new OrderDomainException("Customer not found with id: " + customerId));
    }

    private Restaurant checkRestaurant(CreateOrderCommand createOrderCommand) {
        Restaurant restaurant = orderDataMapper.createOrderCommandToRestaurant(createOrderCommand);
        return restaurantRepository.findRestaurantInformation(restaurant).orElseThrow(() ->
                new OrderDomainException("Restaurant not found"));
    }

    private Order saveOrder(Order order) {
        Order orderResult = orderRepository.save(order);
        if (orderResult == null) {
            throw new OrderDomainException("Could not save order!");
        }
        return orderResult;
    }
}
```

**Правила для Application Service:**

| # | Правило |
|---|---|
| 1 | `ApplicationServiceImpl` — `@Service`, делегирует в handlers |
| 2 | Handlers — `@Component` с `@Transactional` |
| 3 | Один handler на один use case (или группу связанных операций) |
| 4 | Handler получает зависимости через constructor injection (`@RequiredArgsConstructor`) |
| 5 | Handler вызывает Domain Service для бизнес-логики |
| 6 | Handler сохраняет результат через Repository port |
| 7 | Handler записывает outbox-сообщение в той же транзакции |
| 8 | Логирование — на уровне handler, не domain service |

### 4.6. Domain Mapper (Application Layer)

```java
// ✅ Маппер DTO <-> Domain — @Component, ручной маппинг
@Component
public class OrderDataMapper {

    public Order createOrderCommandToOrder(CreateOrderCommand createOrderCommand) {
        return Order.builder()
                .customerId(new CustomerId(createOrderCommand.getCustomerId()))
                .restaurantId(new RestaurantId(createOrderCommand.getRestaurantId()))
                .deliveryAddress(orderAddressToStreetAddress(createOrderCommand.getAddress()))
                .price(new Money(createOrderCommand.getPrice()))
                .items(orderItemsToOrderItemEntities(createOrderCommand.getItems()))
                .build();
    }

    public CreateOrderResponse orderToCreateOrderResponse(Order order, String message) {
        return CreateOrderResponse.builder()
                .orderTrackingId(order.getTrackingId().getValue())
                .orderStatus(order.getOrderStatus())
                .message(message)
                .build();
    }

    // ... приватные хелперы
}
```

---

## 5. Infrastructure Layer — стиль кода

### 5.1. Dataaccess Adapter

#### JPA Entity

```java
// ✅ JPA Entity — отдельный класс от domain entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "orders")
@Entity
public class OrderEntity {
    @Id
    private UUID id;

    private UUID customerId;
    private UUID restaurantId;
    private UUID trackingId;
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;
    private String failureMessages;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItemEntity> items;

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL)
    private OrderAddressEntity address;
}
```

#### Spring Data Repository

```java
// ✅ Spring Data JPA — интерфейс, не класс
public interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {
    Optional<OrderEntity> findByTrackingId(UUID trackingId);
}
```

#### Repository Adapter

```java
// ✅ Адаптер реализует output port
@Component
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;
    private final OrderDataAccessMapper orderDataAccessMapper;

    @Override
    public Order save(Order order) {
        return orderDataAccessMapper.orderEntityToOrder(
                orderJpaRepository.save(
                        orderDataAccessMapper.orderToOrderEntity(order)));
    }

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return orderJpaRepository.findById(orderId.getValue())
                .map(orderDataAccessMapper::orderEntityToOrder);
    }

    @Override
    public Optional<Order> findByTrackingId(TrackingId trackingId) {
        return orderJpaRepository.findByTrackingId(trackingId.getValue())
                .map(orderDataAccessMapper::orderEntityToOrder);
    }
}
```

#### Dataaccess Mapper

```java
// ✅ Маппер Domain <-> JPA Entity — @Component
@Component
public class OrderDataAccessMapper {

    public OrderEntity orderToOrderEntity(Order order) {
        OrderEntity orderEntity = OrderEntity.builder()
                .id(order.getId().getValue())
                .customerId(order.getCustomerId().getValue())
                .restaurantId(order.getRestaurantId().getValue())
                .trackingId(order.getTrackingId().getValue())
                .price(order.getPrice().getAmount())
                .orderStatus(order.getOrderStatus())
                .failureMessages(order.getFailureMessages() != null
                        ? String.join(FAILURE_MESSAGE_DELIMITER, order.getFailureMessages())
                        : "")
                .build();
        // ... set child entities
        return orderEntity;
    }

    public Order orderEntityToOrder(OrderEntity orderEntity) {
        return Order.builder()
                .orderId(new OrderId(orderEntity.getId()))
                .customerId(new CustomerId(orderEntity.getCustomerId()))
                .restaurantId(new RestaurantId(orderEntity.getRestaurantId()))
                .trackingId(new TrackingId(orderEntity.getTrackingId()))
                .price(new Money(orderEntity.getPrice()))
                .orderStatus(orderEntity.getOrderStatus())
                // ... map child entities
                .build();
    }
}
```

**Правила для Dataaccess:**

| # | Правило |
|---|---|
| 1 | JPA Entity и Domain Entity — **разные классы** |
| 2 | JPA Entity: `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor` |
| 3 | Domain Entity: `@Getter @Builder` — нет `@Setter` |
| 4 | Маппинг всегда через отдельный `@Component` mapper |
| 5 | Нет MapStruct — ручной маппинг |
| 6 | Repository adapter: `@Component`, implements output port |
| 7 | Spring Data repository: interface, extends `JpaRepository` |

### 5.2. Messaging Adapter

#### Kafka Consumer (Listener)

```java
// ✅ Kafka Listener — реализует KafkaConsumer, делегирует в input port
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResponseKafkaListener implements KafkaConsumer<PaymentResponseAvroModel> {
    private final PaymentResponseMessageListener paymentResponseMessageListener;
    private final OrderMessagingDataMapper orderMessagingDataMapper;

    @Override
    @KafkaListener(
            id = "${kafka-consumer-config.payment-consumer-group-id}",
            topics = "${order-service.payment-response-topic-name}")
    public void receive(
            @Payload List<PaymentResponseAvroModel> messages,
            @Header(KafkaHeaders.RECEIVED_KEY) List<String> keys,
            @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions,
            @Header(KafkaHeaders.OFFSET) List<Long> offsets) {

        log.info("{} number of payment responses received with keys: {}, partitions: {} and offsets: {}",
                messages.size(), keys, partitions, offsets);

        messages.forEach(paymentResponseAvroModel -> {
            try {
                PaymentStatus paymentStatus = PaymentStatus.valueOf(
                        paymentResponseAvroModel.getPaymentStatus().name());
                if (PaymentStatus.COMPLETED == paymentStatus) {
                    log.info("Processing successful payment for order id: {}",
                            paymentResponseAvroModel.getOrderId());
                    paymentResponseMessageListener.paymentCompleted(
                            orderMessagingDataMapper
                                    .paymentResponseAvroModelToPaymentResponse(paymentResponseAvroModel));
                } else if (PaymentStatus.CANCELLED == paymentStatus || PaymentStatus.FAILED == paymentStatus) {
                    log.info("Processing unsuccessful payment for order id: {}",
                            paymentResponseAvroModel.getOrderId());
                    paymentResponseMessageListener.paymentCancelled(
                            orderMessagingDataMapper
                                    .paymentResponseAvroModelToPaymentResponse(paymentResponseAvroModel));
                }
            } catch (Exception e) {
                log.error("Error processing payment response for order id: {}",
                        paymentResponseAvroModel.getOrderId(), e);
            }
        });
    }
}
```

#### Kafka Producer (Publisher)

```java
// ✅ Kafka Publisher — реализует output port
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentEventKafkaPublisher implements PaymentRequestMessagePublisher {
    private final OrderMessagingDataMapper orderMessagingDataMapper;
    private final KafkaProducer<String, PaymentRequestAvroModel> kafkaProducer;
    private final OrderServiceConfigData orderServiceConfigData;
    private final KafkaMessageHelper kafkaMessageHelper;

    @Override
    public void publish(OrderPaymentOutboxMessage orderPaymentOutboxMessage,
                        BiConsumer<OrderPaymentOutboxMessage, OutboxStatus> outboxCallback) {
        OrderPaymentEventPayload orderPaymentEventPayload =
                kafkaMessageHelper.getOrderEventPayload(
                        orderPaymentOutboxMessage.getPayload(),
                        OrderPaymentEventPayload.class);

        String sagaId = orderPaymentOutboxMessage.getSagaId().toString();

        log.info("Received OrderPaymentOutboxMessage for order id: {} and saga id: {}",
                orderPaymentEventPayload.getOrderId(), sagaId);

        try {
            PaymentRequestAvroModel paymentRequestAvroModel =
                    orderMessagingDataMapper.orderPaymentEventToPaymentRequestAvroModel(
                            sagaId, orderPaymentEventPayload);

            kafkaProducer.send(
                    orderServiceConfigData.getPaymentRequestTopicName(),
                    sagaId,
                    paymentRequestAvroModel,
                    kafkaMessageHelper.getKafkaCallback(
                            orderServiceConfigData.getPaymentRequestTopicName(),
                            paymentRequestAvroModel,
                            orderPaymentOutboxMessage,
                            outboxCallback,
                            orderPaymentEventPayload.getOrderId(),
                            "PaymentRequestAvroModel"));

            log.info("OrderPaymentEventPayload sent to Kafka for order id: {} and saga id: {}",
                    orderPaymentEventPayload.getOrderId(), sagaId);
        } catch (Exception e) {
            log.error("Error while sending OrderPaymentEventPayload to kafka for order id: {} and saga id: {},"
                    + " error: {}", orderPaymentEventPayload.getOrderId(), sagaId, e.getMessage());
        }
    }
}
```

#### Messaging Mapper

```java
// ✅ Маппер Domain DTO <-> Avro — @Component
@Component
public class OrderMessagingDataMapper {

    public PaymentRequestAvroModel orderPaymentEventToPaymentRequestAvroModel(
            String sagaId, OrderPaymentEventPayload orderPaymentEventPayload) {
        return PaymentRequestAvroModel.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setSagaId(sagaId)
                .setCustomerId(orderPaymentEventPayload.getCustomerId())
                .setOrderId(orderPaymentEventPayload.getOrderId())
                .setPrice(orderPaymentEventPayload.getPrice())
                .setCreatedAt(orderPaymentEventPayload.getCreatedAt().toInstant())
                .setPaymentOrderStatus(PaymentOrderStatus.valueOf(
                        orderPaymentEventPayload.getPaymentOrderStatus()))
                .build();
    }

    public PaymentResponse paymentResponseAvroModelToPaymentResponse(
            PaymentResponseAvroModel paymentResponseAvroModel) {
        return PaymentResponse.builder()
                .id(paymentResponseAvroModel.getId())
                .sagaId(paymentResponseAvroModel.getSagaId())
                .orderId(paymentResponseAvroModel.getOrderId())
                .paymentId(paymentResponseAvroModel.getPaymentId())
                .price(paymentResponseAvroModel.getPrice())
                .createdAt(paymentResponseAvroModel.getCreatedAt())
                .paymentStatus(PaymentStatus.valueOf(
                        paymentResponseAvroModel.getPaymentStatus().name()))
                .failureMessages(paymentResponseAvroModel.getFailureMessages())
                .build();
    }
}
```

**Правила для Messaging:**

| # | Правило |
|---|---|
| 1 | Consumer: `@Component`, реализует `KafkaConsumer<AvroModel>` |
| 2 | Consumer делегирует в message listener input port — не содержит бизнес-логику |
| 3 | Publisher: `@Component`, реализует message publisher output port |
| 4 | Publisher использует outbox callback (`BiConsumer`) для обновления статуса |
| 5 | Avro-модели — генерируются из `.avsc` схем, не создаются вручную |
| 6 | Маппинг Avro <-> Domain DTO через отдельный `@Component` mapper |
| 7 | Логирование: keys, partitions, offsets при получении сообщений |
| 8 | `try/catch` в consumer — не терять batch при ошибке одного сообщения |

### 5.3. REST Adapter

```java
// ✅ REST Controller — тонкий, делегирует в input port
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/orders", produces = "application/vnd.api.v1+json")
public class OrderController {
    private final OrderApplicationService orderApplicationService;

    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(
            @RequestBody CreateOrderCommand createOrderCommand) {
        log.info("Creating order for customer: {} at restaurant: {}",
                createOrderCommand.getCustomerId(),
                createOrderCommand.getRestaurantId());
        CreateOrderResponse createOrderResponse =
                orderApplicationService.createOrder(createOrderCommand);
        log.info("Order created with tracking id: {}",
                createOrderResponse.getOrderTrackingId());
        return ResponseEntity.ok(createOrderResponse);
    }

    @GetMapping("/{trackingId}")
    public ResponseEntity<TrackOrderResponse> getOrderByTrackingId(
            @PathVariable UUID trackingId) {
        TrackOrderResponse trackOrderResponse =
                orderApplicationService.trackOrder(
                        TrackOrderQuery.builder()
                                .orderTrackingId(trackingId)
                                .build());
        log.info("Returning order status with tracking id: {}",
                trackOrderResponse.getOrderTrackingId());
        return ResponseEntity.ok(trackOrderResponse);
    }
}
```

**Правила для REST:**

| # | Правило |
|---|---|
| 1 | `@RestController` — тонкий, только маршрутизация |
| 2 | `produces = "application/vnd.api.v1+json"` — версионирование через media type |
| 3 | Делегирует в `OrderApplicationService` (input port) |
| 4 | Не содержит бизнес-логику и не обращается к repositories |
| 5 | Логирование: входящий запрос + результат |

---

## 6. Bootstrap Module — стиль кода

### 6.1. BeanConfiguration

```java
// ✅ Регистрация domain beans — единственное место для domain DI
@Configuration
public class BeanConfiguration {

    @Bean
    public OrderDomainService orderDomainService() {
        return new OrderDomainServiceImpl();
    }
}
```

### 6.2. Application Entry Point

```java
@SpringBootApplication(scanBasePackages = "com.company.order")
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
```

**Правила для Bootstrap:**

| # | Правило |
|---|---|
| 1 | `BeanConfiguration` — единственное место для `@Bean` domain services |
| 2 | `@SpringBootApplication` с явным `scanBasePackages` |
| 3 | Никакой бизнес-логики в bootstrap module |
| 4 | Все Spring-конфигурации (`application.yml`, security, etc.) — здесь |

---

## 7. Именование

### 7.1. Классы

| Тип | Паттерн именования | Пример |
|---|---|---|
| Aggregate Root | `{EntityName}` | `Order`, `Payment` |
| Child Entity | `{EntityName}` | `OrderItem`, `Product` |
| ID Value Object | `{EntityName}Id` | `OrderId`, `PaymentId` |
| Value Object | Описательное имя | `Money`, `StreetAddress` |
| Status Enum | `{EntityName}Status` | `OrderStatus`, `PaymentStatus` |
| Domain Event (base) | `{Entity}Event` | `OrderEvent`, `PaymentEvent` |
| Domain Event (concrete) | `{Entity}{Action}Event` | `OrderCreatedEvent`, `OrderPaidEvent` |
| Domain Exception | `{Service}DomainException` | `OrderDomainException` |
| Domain Service | `{Service}DomainService` | `OrderDomainService` |
| Domain Service Impl | `{Service}DomainServiceImpl` | `OrderDomainServiceImpl` |
| Input Port (service) | `{Entity}ApplicationService` | `OrderApplicationService` |
| Input Port (listener) | `{Event}MessageListener` | `PaymentResponseMessageListener` |
| Output Port (repo) | `{Entity}Repository` | `OrderRepository` |
| Output Port (publisher) | `{Event}MessagePublisher` | `PaymentRequestMessagePublisher` |
| Application Service Impl | `{Entity}ApplicationServiceImpl` | `OrderApplicationServiceImpl` |
| Command Handler | `{Entity}{Action}CommandHandler` | `OrderCreateCommandHandler` |
| Command DTO | `{Action}{Entity}Command` | `CreateOrderCommand` |
| Query DTO | `Track{Entity}Query` | `TrackOrderQuery` |
| Response DTO | `{Action}{Entity}Response` | `CreateOrderResponse` |
| JPA Entity | `{Entity}Entity` | `OrderEntity`, `OrderItemEntity` |
| JPA Repository | `{Entity}JpaRepository` | `OrderJpaRepository` |
| Repository Adapter | `{Entity}RepositoryImpl` | `OrderRepositoryImpl` |
| Domain Mapper | `{Entity}DataMapper` | `OrderDataMapper` |
| Dataaccess Mapper | `{Entity}DataAccessMapper` | `OrderDataAccessMapper` |
| Messaging Mapper | `{Entity}MessagingDataMapper` | `OrderMessagingDataMapper` |
| Kafka Listener | `{Event}KafkaListener` | `PaymentResponseKafkaListener` |
| Kafka Publisher | `{Entity}{Event}KafkaPublisher` | `OrderPaymentEventKafkaPublisher` |
| REST Controller | `{Entity}Controller` | `OrderController` |
| Outbox Model | `{Entity}{Event}OutboxMessage` | `OrderPaymentOutboxMessage` |
| Outbox Scheduler | `{Event}OutboxScheduler` | `PaymentOutboxScheduler` |
| Outbox Helper | `{Event}OutboxHelper` | `PaymentOutboxHelper` |
| Saga Step | `{Entity}{Action}Saga` | `OrderPaymentSaga` |

### 7.2. Пакеты

```
com.company.{context}                                  # Root

com.company.{context}.domain                           # Domain module
    .model                                             # Aggregates, Entities, Value Objects
    .event                                             # Domain Events
    .exception                                         # Domain Exceptions
    .service                                           # Domain Services

com.company.{context}.application                      # Application module
    .port.input                                        # Input Ports (Use Cases)
    .port.output                                       # Output Ports (Repositories, Gateways)
    .usecase                                           # Command/Query Handlers
    .dto                                               # Commands, Queries, Responses
    .mapper                                            # Domain <-> DTO mappers
    .outbox.model                                      # Outbox message models
    .outbox.scheduler                                  # Outbox schedulers

com.company.{context}.infrastructure                   # Infrastructure module
    .adapter.input.rest                                # REST Controllers
    .adapter.input.event                               # Kafka Consumers
    .adapter.output.persistence.entity                 # JPA Entities
    .adapter.output.persistence.repository             # Spring Data Repositories
    .adapter.output.persistence.adapter                # Repository port implementations
    .adapter.output.persistence.mapper                 # Domain <-> JPA mappers
    .adapter.output.messaging.publisher                # Kafka Producers
    .adapter.output.messaging.mapper                   # Domain DTO <-> Avro mappers
    .adapter.output.external                           # External API clients
    .adapter.output.storage                            # File/Object Storage
    .config                                            # Spring Configuration

com.company.{context}.bootstrap                        # Bootstrap module
    .config                                            # BeanConfiguration, SecurityConfig, etc.
```

### 7.3. Maven Modules

| Модуль | Artifact ID |
|---|---|
| Domain | `{context}-domain` |
| Application | `{context}-application` |
| Infrastructure | `{context}-infrastructure` |
| Bootstrap | `{context}-bootstrap` |

---

## 8. Маппинг между слоями

### 8.1. Три уровня маппинга

```
REST Request  ──→  Command DTO  ──→  Domain Entity  ──→  JPA Entity
                  (RestMapper)      (DomainMapper)      (DataAccessMapper)

REST Response ←──  Response DTO ←──  Domain Entity  ←──  JPA Entity
                  (RestMapper)      (DomainMapper)      (DataAccessMapper)

Kafka Avro    ──→  Message DTO  ──→  Domain processing
                  (MessagingMapper)
```

| Граница | Маппер | Расположение |
|---|---|---|
| DTO ↔ Domain | `{Entity}DataMapper` | `application/mapper/` |
| Domain ↔ JPA | `{Entity}DataAccessMapper` | `infrastructure/adapter/output/persistence/mapper/` |
| Avro ↔ DTO | `{Entity}MessagingDataMapper` | `infrastructure/adapter/output/messaging/mapper/` |

### 8.2. Правила маппинга

| # | Правило |
|---|---|
| 1 | Все mappers — `@Component`, constructor injection |
| 2 | Ручной маппинг (без MapStruct, без ModelMapper) |
| 3 | Mapper — stateless, только методы преобразования |
| 4 | Каждый mapper работает только на своей границе |
| 5 | Не пропускать слои: REST контроллер **не** маппит напрямую в JPA entity |

---

## 9. Outbox Pattern

### 9.1. Назначение

Гарантирует exactly-once семантику публикации событий: запись в outbox-таблицу происходит
в той же транзакции, что и изменение domain state.

### 9.2. Компоненты

```
application/
├── outbox/
│   ├── model/
│   │   └── OrderPaymentOutboxMessage.java   # Outbox message model
│   └── scheduler/
│       ├── PaymentOutboxHelper.java         # CRUD для outbox messages
│       ├── PaymentOutboxScheduler.java      # @Scheduled — публикация
│       └── PaymentOutboxCleanerScheduler.java # @Scheduled — очистка
│
ports/output/
│   └── repository/
│       └── PaymentOutboxRepository.java     # Output port для outbox persistence
```

### 9.3. Outbox Message Model

```java
@Getter
@Builder
@AllArgsConstructor
@Setter  // Setter для обновления статуса
public class OrderPaymentOutboxMessage {
    private UUID id;
    private UUID sagaId;
    private ZonedDateTime createdAt;
    private ZonedDateTime processedAt;
    private String type;
    private String payload;  // JSON-serialized event payload
    private SagaStatus sagaStatus;
    private OrderStatus orderStatus;
    private OutboxStatus outboxStatus;  // STARTED → COMPLETED
    private int version;  // Optimistic locking
}
```

### 9.4. Outbox Lifecycle

```
1. Command Handler: сохраняет domain entity + outbox message (STARTED) в одной @Transactional
2. Scheduler: читает STARTED messages, вызывает publisher
3. Publisher: отправляет в Kafka, вызывает callback
4. Callback: обновляет outbox message → COMPLETED
5. Cleaner Scheduler: удаляет COMPLETED messages
```

### 9.5. Outbox Helper

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxHelper {
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<List<OrderPaymentOutboxMessage>> getPaymentOutboxMessageByOutboxStatusAndSagaStatus(
            OutboxStatus outboxStatus, SagaStatus... sagaStatus) {
        return paymentOutboxRepository.findByTypeAndOutboxStatusAndSagaStatus(
                ORDER_SAGA_NAME, outboxStatus, sagaStatus);
    }

    @Transactional
    public void save(OrderPaymentOutboxMessage orderPaymentOutboxMessage) {
        paymentOutboxRepository.save(orderPaymentOutboxMessage);
    }

    @Transactional
    public void savePaymentOutboxMessage(
            OrderPaymentEventPayload orderPaymentEventPayload,
            OrderStatus orderStatus,
            SagaStatus sagaStatus,
            OutboxStatus outboxStatus,
            UUID sagaId) {
        save(OrderPaymentOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(sagaId)
                .createdAt(orderPaymentEventPayload.getCreatedAt())
                .type(ORDER_SAGA_NAME)
                .payload(createPayload(orderPaymentEventPayload))
                .orderStatus(orderStatus)
                .sagaStatus(sagaStatus)
                .outboxStatus(outboxStatus)
                .build());
    }

    @Transactional
    public void deletePaymentOutboxMessageByOutboxStatusAndSagaStatus(
            OutboxStatus outboxStatus, SagaStatus... sagaStatus) {
        paymentOutboxRepository.deleteByTypeAndOutboxStatusAndSagaStatus(
                ORDER_SAGA_NAME, outboxStatus, sagaStatus);
    }

    private String createPayload(OrderPaymentEventPayload orderPaymentEventPayload) {
        try {
            return objectMapper.writeValueAsString(orderPaymentEventPayload);
        } catch (JsonProcessingException e) {
            throw new OrderDomainException("Cannot create payload for outbox message", e);
        }
    }
}
```

### 9.6. Outbox Scheduler

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxScheduler implements OutboxScheduler {
    private final PaymentOutboxHelper paymentOutboxHelper;
    private final PaymentRequestMessagePublisher paymentRequestMessagePublisher;

    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${order-service.outbox-scheduler-fixed-rate}",
               initialDelayString = "${order-service.outbox-scheduler-initial-delay}")
    public void processOutboxMessage() {
        Optional<List<OrderPaymentOutboxMessage>> outboxMessagesResponse =
                paymentOutboxHelper.getPaymentOutboxMessageByOutboxStatusAndSagaStatus(
                        OutboxStatus.STARTED,
                        SagaStatus.STARTED, SagaStatus.COMPENSATING);

        if (outboxMessagesResponse.isPresent() && !outboxMessagesResponse.get().isEmpty()) {
            List<OrderPaymentOutboxMessage> outboxMessages = outboxMessagesResponse.get();
            log.info("Received {} OrderPaymentOutboxMessage with ids: {}, sending to message bus!",
                    outboxMessages.size(),
                    outboxMessages.stream()
                            .map(m -> m.getId().toString())
                            .collect(Collectors.joining(",")));

            outboxMessages.forEach(outboxMessage ->
                    paymentRequestMessagePublisher.publish(outboxMessage, this::updateOutboxStatus));

            log.info("{} OrderPaymentOutboxMessage sent to message bus!", outboxMessages.size());
        }
    }

    private void updateOutboxStatus(OrderPaymentOutboxMessage orderPaymentOutboxMessage,
                                     OutboxStatus outboxStatus) {
        orderPaymentOutboxMessage.setOutboxStatus(outboxStatus);
        paymentOutboxHelper.save(orderPaymentOutboxMessage);
        log.info("OrderPaymentOutboxMessage is updated with outbox status: {}", outboxStatus.name());
    }
}
```

---

## 10. Saga Pattern

### 10.1. SagaStep Interface

```java
// ✅ Из infrastructure/saga — generic step
public interface SagaStep<T> {
    void process(T data);
    void rollback(T data);
}
```

### 10.2. Saga Implementation

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentSaga implements SagaStep<PaymentResponse> {
    private final OrderDomainService orderDomainService;
    private final OrderSagaHelper orderSagaHelper;
    private final PaymentOutboxHelper paymentOutboxHelper;
    private final ApprovalOutboxHelper approvalOutboxHelper;
    private final OrderDataMapper orderDataMapper;

    @Override
    @Transactional
    public void process(PaymentResponse paymentResponse) {
        // 1. Найти outbox message по sagaId
        // 2. Найти order
        // 3. Вызвать domain service (payOrder)
        // 4. Обновить saga status → PROCESSING
        // 5. Записать следующий outbox message (approval request)
    }

    @Override
    @Transactional
    public void rollback(PaymentResponse paymentResponse) {
        // 1. Найти outbox message по sagaId
        // 2. Найти order
        // 3. Вызвать domain service (cancelOrderPayment)
        // 4. Обновить saga status → COMPENSATED
    }
}
```

### 10.3. Saga Status Flow

```
OrderCreated  ──→  PaymentCompleted  ──→  RestaurantApproved  ──→  COMPLETED
     │                    │                       │
     ↓                    ↓                       ↓
  STARTED            PROCESSING              SUCCEEDED
     │                    │                       │
     ↓ (fail)             ↓ (fail)                ↓ (fail)
 COMPENSATING        COMPENSATING            COMPENSATING
     ↓                    ↓                       ↓
 COMPENSATED         COMPENSATED             COMPENSATED
```

| Status | Описание |
|---|---|
| `STARTED` | Saga инициирована, ожидание ответа |
| `PROCESSING` | Промежуточный шаг завершён, ожидание следующего |
| `SUCCEEDED` | Все шаги завершены успешно |
| `COMPENSATING` | Запущена компенсация |
| `COMPENSATED` | Компенсация завершена |

---

## 11. Обработка ошибок

### 11.1. Иерархия исключений

```
DomainException (common-domain)
├── OrderDomainException
├── OrderNotFoundException
├── PaymentDomainException
├── PaymentNotFoundException
├── RestaurantDomainException
└── CustomerDomainException
```

### 11.2. Domain Exceptions

```java
// ✅ В common-domain
public class DomainException extends RuntimeException {
    public DomainException(String message) { super(message); }
    public DomainException(String message, Throwable cause) { super(message, cause); }
}

// ✅ В domain/exception/
public class OrderDomainException extends DomainException {
    public OrderDomainException(String message) { super(message); }
    public OrderDomainException(String message, Throwable cause) { super(message, cause); }
}

public class OrderNotFoundException extends DomainException {
    public OrderNotFoundException(String message) { super(message); }
    public OrderNotFoundException(String message, Throwable cause) { super(message, cause); }
}
```

### 11.3. Global Exception Handler

```java
// ✅ В common-application или REST application module
@Slf4j
@ControllerAdvice
public class OrderGlobalExceptionHandler {

    @ResponseBody
    @ExceptionHandler(value = OrderDomainException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDTO handleException(OrderDomainException orderDomainException) {
        log.error(orderDomainException.getMessage(), orderDomainException);
        return ErrorDTO.builder()
                .code(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(orderDomainException.getMessage())
                .build();
    }

    @ResponseBody
    @ExceptionHandler(value = OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorDTO handleException(OrderNotFoundException orderNotFoundException) {
        log.error(orderNotFoundException.getMessage(), orderNotFoundException);
        return ErrorDTO.builder()
                .code(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(orderNotFoundException.getMessage())
                .build();
    }
}
```

**Правила обработки ошибок:**

| # | Правило |
|---|---|
| 1 | Domain exceptions — `RuntimeException`, не checked |
| 2 | Один base exception на сервис (`{Service}DomainException`) |
| 3 | `NotFoundException` — отдельный класс для 404 |
| 4 | Guard clause в бизнес-методах: бросает domain exception при нарушении инварианта |
| 5 | `@ControllerAdvice` — централизованная обработка в REST layer |
| 6 | Kafka consumers: `try/catch` на каждое сообщение, log error, continue processing |

---

## 12. Тестирование

### 12.1. Стратегия по слоям

| Слой | Тип теста | Что тестируем |
|---|---|---|
| Domain | Unit | Бизнес-логика агрегатов, domain service |
| Application | Unit + Integration | Command handlers, saga steps |
| Dataaccess | Integration | Repository adapters (Testcontainers) |
| Messaging | Integration | Kafka consumers/producers (Embedded Kafka) |
| REST | Integration | Controllers (MockMvc / WebTestClient) |
| E2E | Integration | Полный flow через все слои |

### 12.2. Domain Tests

```java
// ✅ Чистые unit-тесты — нет Spring, нет моков инфраструктуры
class OrderDomainServiceTest {
    private OrderDomainService orderDomainService;

    @BeforeEach
    void init() {
        orderDomainService = new OrderDomainServiceImpl();
    }

    @Test
    void testCreateOrder() {
        // Given
        Restaurant restaurant = createRestaurant();
        Order order = createOrder();

        // When
        OrderCreatedEvent event = orderDomainService.validateAndInitiateOrder(order, restaurant);

        // Then
        assertEquals(OrderStatus.PENDING, order.getOrderStatus());
        assertNotNull(event.getCreatedAt());
    }

    @Test
    void testCreateOrderWithWrongTotalPrice() {
        // Given
        Order order = createOrderWithWrongPrice();
        Restaurant restaurant = createRestaurant();

        // When/Then
        assertThrows(OrderDomainException.class,
                () -> orderDomainService.validateAndInitiateOrder(order, restaurant));
    }
}
```

### 12.3. Application Service Tests

```java
// ✅ Integration test с Spring context и мокированными output ports
@SpringBootTest(classes = OrderConfigurationTest.class)
class OrderApplicationServiceTest {

    @Autowired
    private OrderApplicationService orderApplicationService;

    @MockBean
    private OrderRepository orderRepository;

    @MockBean
    private CustomerRepository customerRepository;

    @MockBean
    private RestaurantRepository restaurantRepository;

    @MockBean
    private PaymentRequestMessagePublisher paymentRequestMessagePublisher;

    @Test
    void testCreateOrder() {
        // Given
        when(customerRepository.findCustomer(CUSTOMER_ID))
                .thenReturn(Optional.of(new Customer()));
        when(restaurantRepository.findRestaurantInformation(any()))
                .thenReturn(Optional.of(createRestaurant()));
        when(orderRepository.save(any(Order.class)))
                .thenReturn(createOrder());

        // When
        CreateOrderResponse response = orderApplicationService.createOrder(createOrderCommand);

        // Then
        assertEquals(OrderStatus.PENDING, response.getOrderStatus());
        assertNotNull(response.getOrderTrackingId());
    }
}
```

---

## 13. Чеклисты

### 13.1. Добавление нового сервиса

- [ ] Создать 4 Maven-модуля: `domain`, `application`, `infrastructure`, `bootstrap`
- [ ] Определить aggregate root, child entities, value objects в `domain/model/`
- [ ] Определить domain events в `domain/event/`
- [ ] Создать domain service (interface + impl) в `domain/service/`
- [ ] Определить input ports (service + message listener) в `application/port/input/`
- [ ] Определить output ports (repository + message publisher) в `application/port/output/`
- [ ] Создать DTO (commands, queries, responses) в `application/dto/`
- [ ] Создать domain mapper в `application/mapper/`
- [ ] Реализовать command/query handlers в `application/usecase/`
- [ ] Реализовать application service (делегирует в handlers)
- [ ] Реализовать JPA entities, Spring Data repos, mapper, adapter в `infrastructure/adapter/output/persistence/`
- [ ] Реализовать Kafka listeners в `infrastructure/adapter/input/event/`
- [ ] Реализовать Kafka publishers в `infrastructure/adapter/output/messaging/`
- [ ] Создать REST controller в `infrastructure/adapter/input/rest/`
- [ ] Создать `BeanConfiguration` и `@SpringBootApplication` в `bootstrap/`
- [ ] Настроить Maven зависимости по правилу inward dependency
- [ ] Добавить outbox/saga компоненты для distributed transactions

### 13.2. Добавление нового агрегата в существующий сервис

- [ ] Создать aggregate root (`extends AggregateRoot<{Name}Id>`)
- [ ] Создать ID value object (`extends BaseId<UUID>`)
- [ ] Добавить domain events (если нужно)
- [ ] Добавить методы в domain service (или создать новый)
- [ ] Создать repository output port
- [ ] Добавить DTO (commands, queries, responses)
- [ ] Добавить command handler(s)
- [ ] Расширить application service interface + implementation
- [ ] Создать JPA entity, Spring Data repo, mapper, adapter в dataaccess
- [ ] Добавить REST endpoint(s)

### 13.3. Code Review Checklist

- [ ] Domain-core не имеет Spring-зависимостей
- [ ] Зависимости направлены внутрь (infrastructure → domain)
- [ ] Все порты — интерфейсы в application module
- [ ] Все адаптеры — `@Component` в infrastructure
- [ ] Domain Service зарегистрирован как `@Bean` в BeanConfiguration
- [ ] Бизнес-логика в агрегатах / domain service, не в handlers
- [ ] DTOs immutable (`@Getter @Builder`, нет `@Setter`)
- [ ] Маппинг через отдельные mapper-классы на каждой границе
- [ ] `@Transactional` на handler, не на domain service
- [ ] Outbox message записывается в той же транзакции, что и domain change
- [ ] Kafka consumer обрабатывает ошибки без потери batch
- [ ] Именование соответствует таблице конвенций

---

## Краткая сводка: что где живёт

| Что | Где | Spring? |
|---|---|---|
| Aggregate Root | `domain/model/` | Нет |
| Value Object | `domain/model/` | Нет |
| Domain Event | `domain/event/` | Нет |
| Domain Service | `domain/service/` | Нет (`@Bean` в bootstrap) |
| Domain Exception | `domain/exception/` | Нет |
| Input Port | `application/port/input/` | Нет (interface) |
| Output Port | `application/port/output/` | Нет (interface) |
| Command DTO | `application/dto/` | `@NotNull` только |
| Command Handler | `application/usecase/` | `@Component @Transactional` |
| Application Service | `application/` | `@Service @Validated` |
| Domain Mapper | `application/mapper/` | `@Component` |
| Outbox Helper | `application/outbox/` | `@Component @Transactional` |
| Outbox Scheduler | `application/outbox/scheduler/` | `@Component @Scheduled` |
| Saga Step | `application/usecase/` | `@Component @Transactional` |
| JPA Entity | `infrastructure/adapter/output/persistence/entity/` | `@Entity @Table` |
| JPA Repository | `infrastructure/adapter/output/persistence/repository/` | `JpaRepository` |
| Repository Adapter | `infrastructure/adapter/output/persistence/adapter/` | `@Component` |
| Dataaccess Mapper | `infrastructure/adapter/output/persistence/mapper/` | `@Component` |
| Kafka Listener | `infrastructure/adapter/input/event/` | `@Component @KafkaListener` |
| Kafka Publisher | `infrastructure/adapter/output/messaging/publisher/` | `@Component` |
| Messaging Mapper | `infrastructure/adapter/output/messaging/mapper/` | `@Component` |
| REST Controller | `infrastructure/adapter/input/rest/` | `@RestController` |
| BeanConfiguration | `bootstrap/config/` | `@Configuration @Bean` |
| Application Main | `bootstrap/` | `@SpringBootApplication` |
