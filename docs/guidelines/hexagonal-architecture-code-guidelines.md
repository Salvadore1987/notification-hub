# Code Guidelines: Hexagonal Architecture

> Руководство по организации кода, структуре модулей и каталогов для Spring Boot проектов

---

## Содержание

- [Часть 1. Гексагональная архитектура с DDD](#часть-1-гексагональная-архитектура-с-ddd)
- [Часть 2. Гексагональная архитектура без DDD](#часть-2-гексагональная-архитектура-без-ddd)
- [Часть 3. Гексагональная архитектура с CQRS](#часть-3-гексагональная-архитектура-с-cqrs)
- [Общие принципы](#общие-принципы)

---

## Часть 1. Гексагональная архитектура с DDD

### 1.1. Обзор

Гексагональная архитектура (Ports & Adapters) в сочетании с Domain-Driven Design предполагает выделение
богатой доменной модели в центр приложения. Домен полностью изолирован от инфраструктуры и фреймворка.
Бизнес-логика живёт в доменных сущностях, Value Objects и Domain Services, а не в Application Services.
Порты (интерфейсы для взаимодействия с внешним миром) определяются в слое Application.

### 1.2. Слои и их ответственность

| Слой | Ответственность | Зависимости |
|---|---|---|
| **Domain** | Бизнес-правила, сущности, Value Objects, Domain Events, Domain Services | Ни от чего (чистая Java) |
| **Application** | Определение портов, оркестрация use case'ов, транзакции | Зависит от Domain |
| **Infrastructure** | Реализация портов (БД, REST-клиенты, очереди, файлы) | Зависит от Application (и транзитивно от Domain) |
| **Bootstrap** | Конфигурация Spring, сборка приложения, DI wiring | Зависит от всех слоёв |

### 1.3. Структура модулей и каталогов

```
order-service/                                  # Root project
├── pom.xml                                     # Parent POM (module declarations)
│
├── order-domain/                               # Модуль Domain — чистая Java, никаких фреймворков
│   ├── pom.xml                                 # Зависимости: ничего
│   └── src/main/java/
│       └── com/company/order/domain/
│           ├── model/                          # Агрегаты, сущности и Value Objects
│           │   ├── order/                      # Агрегат Order
│           │   │   ├── Order.java              # Aggregate Root
│           │   │   ├── OrderId.java            # Value Object — идентификатор
│           │   │   ├── OrderStatus.java        # Enum статусов
│           │   │   ├── OrderLine.java          # Entity внутри агрегата
│           │   │   └── Money.java              # Value Object
│           │   └── customer/                   # Агрегат Customer
│           │       ├── Customer.java           # Aggregate Root
│           │       ├── CustomerId.java         # Value Object — идентификатор
│           │       ├── Email.java              # Value Object с валидацией
│           │       └── Address.java            # Value Object
│           ├── event/                          # Domain Events
│           │   ├── DomainEvent.java            # Базовый маркер-интерфейс
│           │   ├── OrderCreatedEvent.java
│           │   └── OrderCancelledEvent.java
│           ├── service/                        # Domain Services
│           │   ├── OrderPricingService.java    # Логика, не принадлежащая одному агрегату
│           │   └── DiscountPolicy.java         # Стратегия/политика домена
│           └── exception/                      # Доменные исключения
│               ├── DomainException.java
│               ├── OrderNotFoundException.java
│               └── InsufficientFundsException.java
│
├── order-application/                          # Модуль Application — оркестрация use case'ов
│   ├── pom.xml                                 # Зависимости: order-domain
│   └── src/main/java/
│       └── com/company/order/application/
│           ├── port/                           # Порты (интерфейсы)
│           │   ├── in/                         # Входящие порты (Driving)
│           │   │   ├── CreateOrderUseCase.java
│           │   │   ├── CancelOrderUseCase.java
│           │   │   └── GetOrderQuery.java
│           │   └── out/                        # Исходящие порты (Driven)
│           │       ├── OrderRepository.java    # Persistence port
│           │       ├── PaymentGateway.java     # External system port
│           │       ├── NotificationSender.java # Messaging port
│           │       └── EventPublisher.java     # Domain event publishing port
│           ├── usecase/                        # Реализации входящих портов
│           │   ├── CreateOrderUseCaseImpl.java
│           │   ├── CancelOrderUseCaseImpl.java
│           │   └── GetOrderQueryImpl.java
│           ├── dto/                            # Объекты команд и результатов
│           │   ├── command/
│           │   │   ├── CreateOrderCommand.java
│           │   │   └── CancelOrderCommand.java
│           │   ├── query/
│           │   │   └── OrderCriteria.java
│           │   └── result/
│           │       ├── OrderResult.java
│           │       └── OrderSummaryResult.java
│           └── mapper/                         # Маппинг domain ↔ dto
│               └── OrderApplicationMapper.java
│
├── order-infrastructure/                       # Модуль Infrastructure — адаптеры
│   ├── pom.xml                                 # Зависимости: order-application, spring-*
│   └── src/
│       ├── main/java/
│       │   └── com/company/order/infrastructure/
│       │       ├── in/                         # Driving-адаптеры (входящие)
│       │       │   ├── rest/
│       │       │   │   ├── OrderController.java
│       │       │   │   ├── OrderRestMapper.java
│       │       │   │   ├── request/
│       │       │   │   │   └── CreateOrderRequest.java
│       │       │   │   ├── response/
│       │       │   │   │   └── OrderResponse.java
│       │       │   │   └── handler/
│       │       │   │       └── OrderExceptionHandler.java
│       │       │   ├── grpc/
│       │       │   │   └── OrderGrpcService.java
│       │       │   ├── messaging/
│       │       │   │   └── OrderEventListener.java
│       │       │   └── scheduler/
│       │       │       └── OrderCleanupScheduler.java
│       │       ├── out/                        # Driven-адаптеры (исходящие)
│       │       │   ├── persistence/
│       │       │   │   ├── OrderPersistenceAdapter.java  # Реализует OrderRepository
│       │       │   │   ├── OrderJpaRepository.java       # Spring Data интерфейс
│       │       │   │   ├── entity/
│       │       │   │   │   ├── OrderJpaEntity.java
│       │       │   │   │   └── OrderLineJpaEntity.java
│       │       │   │   └── mapper/
│       │       │   │       └── OrderPersistenceMapper.java
│       │       │   ├── payment/
│       │       │   │   ├── PaymentGatewayAdapter.java    # Реализует PaymentGateway
│       │       │   │   ├── PaymentClient.java
│       │       │   │   └── dto/
│       │       │   │       └── PaymentRequestDto.java
│       │       │   ├── notification/
│       │       │   │   └── EmailNotificationAdapter.java # Реализует NotificationSender
│       │       │   └── event/
│       │       │       └── SpringEventPublisher.java     # Реализует EventPublisher
│       │       └── config/                     # Инфраструктурная конфигурация
│       │           ├── JpaConfig.java
│       │           ├── RestClientConfig.java
│       │           └── KafkaConfig.java
│       └── main/resources/
│           └── db/migration/                   # Flyway / Liquibase миграции
│               ├── V001__create_orders.sql
│               └── V002__create_order_lines.sql
│
└── order-bootstrap/                            # Модуль Bootstrap — точка входа и сборка
    ├── pom.xml                                 # Зависимости: order-infrastructure (транзитивно все)
    └── src/
        ├── main/java/
        │   └── com/company/order/bootstrap/
        │       ├── Application.java            # @SpringBootApplication
        │       └── config/
        │           ├── BeanConfig.java         # Ручной wiring доменных и application бинов
        │           ├── SecurityConfig.java
        │           └── SwaggerConfig.java
        └── main/resources/
            ├── application.yml
            └── application-dev.yml
```

### 1.4. Правило зависимостей

```
Bootstrap → Infrastructure → Application → Domain
```

Стрелки показывают направление зависимостей. Domain не зависит ни от кого. Application зависит
от Domain (использует доменные объекты) и определяет порты. Infrastructure зависит от Application
(реализует исходящие порты, использует DTO). Bootstrap знает обо всех слоях и связывает их через DI.

### 1.5. Правила кодирования по слоям

#### Domain

```java
// ✅ Aggregate Root — богатая модель с бизнес-логикой
public class Order {

    private OrderId id;
    private CustomerId customerId;
    private List<OrderLine> lines;
    private OrderStatus status;
    private Money totalPrice;

    // Фабричный метод вместо публичного конструктора
    public static Order create(CustomerId customerId, List<OrderLine> lines) {
        Order order = new Order();
        order.id = OrderId.generate();
        order.customerId = customerId;
        order.lines = List.copyOf(lines);
        order.status = OrderStatus.CREATED;
        order.totalPrice = order.calculateTotal();
        return order;
    }

    // Бизнес-метод с инвариантами
    public void cancel() {
        if (this.status == OrderStatus.SHIPPED) {
            throw new DomainException("Cannot cancel shipped order");
        }
        this.status = OrderStatus.CANCELLED;
    }

    private Money calculateTotal() {
        return lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(Money.ZERO, Money::add);
    }
}
```

```java
// ✅ Value Object — неизменяемый, с self-validation
public record Email(String value) {

    public Email {
        if (value == null || !value.matches("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$")) {
            throw new DomainException("Invalid email: " + value);
        }
    }
}
```

**Запрещено в Domain:**

- Аннотации Spring (`@Component`, `@Service`, `@Transactional`)
- Аннотации JPA (`@Entity`, `@Table`, `@Column`)
- Любые зависимости на фреймворки и библиотеки инфраструктурного уровня (исключение — Lombok: допустим как compile-time инструмент, не попадает в рантайм)
- Порты и интерфейсы для внешних систем (определяются в Application)
- Анемичные модели (сущности только с геттерами/сеттерами без поведения)
- Ключевое слово `var` — все переменные с явным указанием типа

#### Application

```java
// ✅ Входящий порт (Driving) — определяет use case
public interface CreateOrderUseCase {
    OrderResult execute(CreateOrderCommand command);
}
```

```java
// ✅ Исходящий порт (Driven) — интерфейс в application, реализация в infrastructure
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    void save(Order order);
    void delete(OrderId id);
}
```

```java
// ✅ Use Case — оркестрация, не содержит бизнес-логики
@RequiredArgsConstructor
public class CreateOrderUseCaseImpl implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final OrderDomainService orderDomainService;
    private final OrderApplicationMapper mapper;

    @Override
    @Transactional
    public OrderResult execute(CreateOrderCommand command) {
        // 1. Маппинг команды → доменные объекты
        CustomerId customerId = new CustomerId(command.customerId());
        List<OrderLine> lines = mapper.toOrderLines(command.items());

        // 2. Вызов доменной логики (DomainService создаёт агрегат и публикует события)
        Order order = orderDomainService.createOrder(customerId, lines);

        // 3. Вызов исходящих портов
        paymentGateway.authorize(order.totalPrice(), customerId);
        orderRepository.save(order);

        // 4. Возврат результата
        return mapper.toResult(order);
    }
}
```

**Запрещено в Application:**

- Бизнес-логика (должна быть в Domain)
- Прямое использование инфраструктурных классов (только через порты)
- HTTP-специфичные объекты (HttpServletRequest, ResponseEntity)
- Знание о конкретных технологиях хранения (JPA, JDBC)
- Ключевое слово `var` — все переменные с явным указанием типа

#### Infrastructure

```java
// ✅ Outbound-адаптер — реализует порт из application
@Repository
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements OrderRepository {

    private final OrderJpaRepository jpaRepository;
    private final OrderPersistenceMapper mapper;

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpaRepository.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public void save(Order order) {
        OrderJpaEntity entity = mapper.toJpaEntity(order);
        jpaRepository.save(entity);
    }

    @Override
    public void delete(OrderId id) {
        jpaRepository.deleteById(id.value());
    }
}
```

```java
// ✅ Inbound-адаптер — вызывает входящий порт
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final GetOrderQuery getOrderQuery;
    private final OrderRestMapper mapper;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = mapper.toCommand(request);
        OrderResult result = createOrderUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(result));
    }
}
```

```java
// ✅ JPA Entity — отдельная от доменной модели
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class OrderJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderLineJpaEntity> lines = new ArrayList<>();
}
```

#### Bootstrap

```java
// ✅ Ручной wiring доменных бинов (они без аннотаций Spring)
@Configuration
public class BeanConfig {

    @Bean
    public CreateOrderUseCase createOrderUseCase(
            OrderRepository orderRepository,
            PaymentGateway paymentGateway,
            EventPublisher eventPublisher,
            OrderApplicationMapper mapper) {
        return new CreateOrderUseCaseImpl(orderRepository, paymentGateway, eventPublisher, mapper);
    }

    @Bean
    public OrderPricingService orderPricingService() {
        return new OrderPricingService();
    }
}
```

### 1.6. Базовые классы и события доменной модели

Доменная модель строится на иерархии базовых абстракций. Все классы живут в модуле `domain`
и не имеют зависимостей на фреймворки (кроме Lombok как compile-time инструмента).

Иерархия наследования: [UML-диаграмма](diagrams/domain-model-hierarchy.puml)

#### BaseId — типизированный идентификатор

Неизменяемый объект-обёртка над примитивным идентификатором. Каждый агрегат определяет свой
наследник (`PaymentId`, `OrderId` и т.д.), что исключает случайную подмену идентификаторов
разных агрегатов на уровне типов.

```java
public abstract class BaseId<T> implements Serializable {
  private final T value;

  protected BaseId(final T value) {
    this.value = value;
  }

  public T getValue() {
    return value;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof BaseId<?> baseId)) return false;
    return Objects.equals(value, baseId.value);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(value);
  }
}
```

#### BaseEntity — абстрактная сущность

Базовый класс для всех сущностей домена. Идентичность определяется по `id` —
две сущности с одинаковым `id` считаются равными независимо от остальных полей.
`@SuperBuilder` обязателен для корректной работы Lombok Builder в наследниках.

```java
@SuperBuilder
public abstract class BaseEntity<T> implements Serializable {
  private T id;

  public BaseEntity() {

  }

  public T getId() {
    return id;
  }

  public void setId(final T id) {
    this.id = id;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof BaseEntity<?> that)) return false;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
```

#### AggregateRoot — маркер корня агрегата

Корень агрегата — граница транзакционной консистентности. Только агрегаты сохраняются через
репозитории. Сущность хранит исключительно бизнес-данные; публикация событий —
ответственность `DomainService`.

```java
@SuperBuilder
public abstract class AggregateRoot<T> extends BaseEntity<T> {

  public AggregateRoot() {

  }
}
```

#### DomainEvent — интерфейс доменного события

Событие — иммутабельный факт, произошедший в домене. Метод `fire()` делегирует публикацию
в `DomainEventPublisher`, что позволяет доменному коду инициировать события без зависимости
на инфраструктуру. Вызов `fire()` выполняется из `DomainService`, а не из сущности.

```java
public interface DomainEvent {
  void fire();
}
```

**DomainEventPublisher** — generic-интерфейс для публикации событий. Определяется в domain,
реализуется в infrastructure. Типизация `<T extends DomainEvent>` гарантирует, что каждый
publisher работает только со своим типом события.

```java
public interface DomainEventPublisher<T extends DomainEvent> {
  void publish(T domainEvent);
}
```

**EmptyEvent** — заглушка для случаев, когда бизнес-операция не порождает события.
Singleton, избавляет от `null`-проверок и Optional-обёрток при работе с событиями.

```java
public final class EmptyEvent implements DomainEvent<Void> {

  public static final EmptyEvent INSTANCE = new EmptyEvent();

  private EmptyEvent() {
  }

  @Override
  public void fire() {

  }
}
```

#### Пример реализации: Payment aggregate

Ниже — полный пример агрегата `Payment` с типизированным идентификатором, иерархией событий
и `DomainService` для публикации.

**PaymentId** — типизированный идентификатор платежа:

```java
public class PaymentId extends BaseId<UUID> {

    public PaymentId(UUID value) {
        super(value);
    }

    // UUIDv7 — time-ordered, подходит для использования как PK в БД
    public static PaymentId generate() {
        UUID uuidV7 = Generators.timeBasedEpochGenerator().generate();
        return new PaymentId(uuidV7);
    }
}
```

> **Почему UUIDv7, а не UUIDv4?**
> UUIDv7 (RFC 9562) содержит метку времени в старших битах, поэтому значения монотонно возрастают.
> Это критически важно для B-Tree индексов в БД: вставки идут «в конец» дерева, что устраняет
> random page splits и значительно снижает write amplification. Для генерации используется
> `com.fasterxml.uuid:java-uuid-generator` (`Generators.timeBasedEpochGenerator().generate()`).

**Payment** — агрегат с бизнес-логикой:

```java
@Getter
@SuperBuilder
public class Payment extends AggregateRoot<PaymentId> {

    private final Money amount;
    private final CustomerId customerId;
    private PaymentStatus status;

    // Приватный конструктор — создание только через фабричный метод
    private Payment(PaymentId id, Money amount, CustomerId customerId, PaymentStatus status) {
        super(id);
        this.amount = amount;
        this.customerId = customerId;
        this.status = status;
    }

    // ✅ Фабричный метод — единственная точка создания. UUIDv7, self-validation.
    public static Payment create(Money amount, CustomerId customerId) {
        if (amount == null || !amount.isPositive()) {
            throw new DomainException("Payment amount must be positive");
        }
        return new Payment(PaymentId.generate(), amount, customerId, PaymentStatus.CREATED);
    }

    // ✅ Бизнес-метод с guard clause
    public void confirm() {
        if (this.status != PaymentStatus.CREATED) {
            throw new DomainException("Only created payments can be confirmed");
        }
        this.status = PaymentStatus.CONFIRMED;
    }

    // ✅ Бизнес-метод
    public void cancel() {
        if (this.status == PaymentStatus.CONFIRMED) {
            throw new DomainException("Cannot cancel confirmed payment");
        }
        this.status = PaymentStatus.CANCELLED;
    }
}
```

**PaymentEvent** — базовое событие агрегата Payment:

Промежуточный абстрактный класс для всех событий, связанных с `Payment`.
Содержит ссылку на агрегат и временную метку — общие поля для любого события платежа.

```java
@Getter
@ToString
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public abstract class PaymentEvent implements DomainEvent {
  Payment payment;
  LocalDateTime createdAt;
}
```

**PaymentCreatedEvent** — конкретное доменное событие:

Наследует `PaymentEvent`, добавляет ссылку на `DomainEventPublisher`. Если publisher
не зарегистрирован — `fire()` бросает `PublisherNotPresentException`, что гарантирует
корректную конфигурацию при старте приложения.

```java
public class PaymentCreatedEvent extends PaymentEvent {

    DomainEventPublisher<PaymentCreatedEvent> publisher;

    public PaymentCreatedEvent(final Payment payment,
                               final LocalDateTime createdAt,
                               final DomainEventPublisher<PaymentCreatedEvent> publisher) {
        super(payment, createdAt);
        this.publisher = publisher;
    }

    @Override
    public void fire() {
        if (publisher == null) {
            throw new PublisherNotPresentException();
        }
    }
}
```

**PaymentCreatedMessagePublisher** — порт для публикации `PaymentCreatedEvent`.
Определяется в application (ports/out), реализуется в infrastructure (например, через Kafka или RabbitMQ).

```java
public interface PaymentCreatedMessagePublisher extends DomainEventPublisher<PaymentCreatedEvent> {
}
```

**PaymentDomainService** — оркестрация и публикация событий:

`DomainService` создаёт агрегат и публикует события. Сущности остаются чистыми —
только данные и бизнес-правила, без побочных эффектов.

```java
public class PaymentDomainService {

    public Payment createPayment(Money amount, CustomerId customerId) {
        Payment payment = Payment.create(amount, customerId);
        new PaymentCreatedEvent(payment.getId(), amount, customerId).fire();
        return payment;
    }
}
```

### 1.7. Правила маппинга

В DDD-варианте существуют три точки маппинга с отдельными маппер-классами:

| Граница | Маппер | Что маппит |
|---|---|---|
| REST → Application | `OrderRestMapper` | `CreateOrderRequest` → `CreateOrderCommand`, `OrderResult` → `OrderResponse` |
| Application → Domain | `OrderApplicationMapper` | `CreateOrderCommand` → доменные объекты, `Order` → `OrderResult` |
| Domain ↔ Persistence | `OrderPersistenceMapper` | `Order` ↔ `OrderJpaEntity` |

---

## Часть 2. Гексагональная архитектура без DDD

### 2.1. Обзор

Облегчённый вариант гексагональной архитектуры без выделенного доменного слоя. Бизнес-логика
сосредоточена в Application Services. Модель данных — анемичная (DTO/Record), без поведения.
Подходит для CRUD-тяжёлых сервисов, интеграционных микросервисов и проектов с простой бизнес-логикой.

### 2.2. Слои и их ответственность

| Слой | Ответственность | Зависимости |
|---|---|---|
| **Application** | Бизнес-логика, use case'ы, порты (интерфейсы), модели данных | Ни от чего (самодостаточный) |
| **Infrastructure** | Реализация портов, контроллеры, persistence, внешние клиенты | Зависит от Application |
| **Bootstrap** | Конфигурация Spring, сборка приложения, DI wiring | Зависит от всех слоёв |

### 2.3. Структура модулей и каталогов

```
order-service/                                  # Root project
├── pom.xml                                     # Parent POM (module declarations)
│
├── order-application/                          # Модуль Application — ядро (бизнес-логика + порты)
│   ├── pom.xml                                 # Зависимости: ничего (чистая Java)
│   └── src/main/java/
│       └── com/company/order/application/
│           ├── port/                           # Порты (интерфейсы)
│           │   ├── in/                         # Входящие порты (Driving)
│           │   │   ├── CreateOrderUseCase.java
│           │   │   ├── CancelOrderUseCase.java
│           │   │   └── GetOrderQuery.java
│           │   └── out/                        # Исходящие порты (Driven)
│           │       ├── OrderPersistencePort.java
│           │       ├── PaymentPort.java
│           │       └── NotificationPort.java
│           ├── service/                        # Реализации use case'ов (бизнес-логика)
│           │   ├── CreateOrderService.java
│           │   ├── CancelOrderService.java
│           │   └── GetOrderQueryService.java
│           ├── model/                          # Модели данных ядра (не JPA-сущности!)
│           │   ├── Order.java                  # Record / POJO без аннотаций фреймворка
│           │   ├── OrderLine.java
│           │   ├── OrderStatus.java
│           │   └── Customer.java
│           ├── dto/                            # Команды, запросы, результаты
│           │   ├── command/
│           │   │   ├── CreateOrderCommand.java
│           │   │   └── CancelOrderCommand.java
│           │   ├── query/
│           │   │   └── OrderCriteria.java
│           │   └── result/
│           │       ├── OrderResult.java
│           │       └── OrderSummaryResult.java
│           └── exception/                      # Бизнес-исключения
│               ├── ApplicationException.java
│               ├── OrderNotFoundException.java
│               └── PaymentFailedException.java
│
├── order-infrastructure/                       # Модуль Infrastructure — адаптеры
│   ├── pom.xml                                 # Зависимости: order-application, spring-*
│   └── src/
│       ├── main/java/
│       │   └── com/company/order/infrastructure/
│       │       ├── in/                         # Driving-адаптеры
│       │       │   ├── rest/
│       │       │   │   ├── OrderController.java
│       │       │   │   ├── OrderRestMapper.java
│       │       │   │   ├── request/
│       │       │   │   │   └── CreateOrderRequest.java
│       │       │   │   ├── response/
│       │       │   │   │   └── OrderResponse.java
│       │       │   │   └── handler/
│       │       │   │       └── OrderExceptionHandler.java
│       │       │   ├── messaging/
│       │       │   │   └── OrderEventListener.java
│       │       │   └── scheduler/
│       │       │       └── OrderCleanupScheduler.java
│       │       ├── out/                        # Driven-адаптеры
│       │       │   ├── persistence/
│       │       │   │   ├── OrderPersistenceAdapter.java  # Реализует OrderPersistencePort
│       │       │   │   ├── OrderJpaRepository.java       # Spring Data интерфейс
│       │       │   │   ├── entity/
│       │       │   │   │   ├── OrderJpaEntity.java
│       │       │   │   │   └── OrderLineJpaEntity.java
│       │       │   │   └── mapper/
│       │       │   │       └── OrderPersistenceMapper.java
│       │       │   ├── payment/
│       │       │   │   ├── PaymentAdapter.java           # Реализует PaymentPort
│       │       │   │   └── dto/
│       │       │   │       └── PaymentRequestDto.java
│       │       │   └── notification/
│       │       │       └── EmailNotificationAdapter.java # Реализует NotificationPort
│       │       └── config/                     # Инфраструктурная конфигурация
│       │           ├── JpaConfig.java
│       │           └── RestClientConfig.java
│       └── main/resources/
│           └── db/migration/                   # Flyway / Liquibase миграции
│               ├── V001__create_orders.sql
│               └── V002__create_order_lines.sql
│
└── order-bootstrap/                            # Модуль Bootstrap — точка входа и сборка
    ├── pom.xml                                 # Зависимости: order-infrastructure (транзитивно все)
    └── src/
        ├── main/java/
        │   └── com/company/order/bootstrap/
        │       ├── Application.java            # @SpringBootApplication
        │       └── config/
        │           ├── BeanConfig.java         # Wiring application-бинов
        │           ├── SecurityConfig.java
        │           └── SwaggerConfig.java
        └── main/resources/
            ├── application.yml
            └── application-dev.yml
```

### 2.4. Правило зависимостей

```
Bootstrap → Infrastructure → Application
```

Application — самодостаточный слой. Infrastructure знает об Application (реализует его порты).
Bootstrap связывает всё через DI.

### 2.5. Правила кодирования по слоям

#### Application

```java
// ✅ Application Model — анемичная модель без фреймворков
public record Order(
    UUID id,
    UUID customerId,
    List<OrderLine> lines,
    OrderStatus status,
    BigDecimal totalPrice,
    Instant createdAt
) {}
```

```java
// ✅ Входящий порт
public interface CreateOrderUseCase {
    OrderResult execute(CreateOrderCommand command);
}
```

```java
// ✅ Исходящий порт
public interface OrderPersistencePort {
    Optional<Order> findById(UUID id);
    Order save(Order order);
    void deleteById(UUID id);
    List<Order> findByCustomerId(UUID customerId);
}
```

```java
// ✅ Application Service — содержит бизнес-логику
@RequiredArgsConstructor
public class CreateOrderService implements CreateOrderUseCase {

    private final OrderPersistencePort persistencePort;
    private final PaymentPort paymentPort;

    @Override
    @Transactional
    public OrderResult execute(CreateOrderCommand command) {
        // Бизнес-логика прямо здесь
        BigDecimal totalPrice = command.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException("Order total must be positive");
        }

        // Вызов исходящих портов
        paymentPort.authorize(command.customerId(), totalPrice);

        Order order = new Order(
                Generators.timeBasedEpochGenerator().generate(), // UUIDv7
                command.customerId(),
                mapToOrderLines(command.items()),
                OrderStatus.CREATED,
                totalPrice,
                Instant.now()
        );

        Order saved = persistencePort.save(order);
        return toResult(saved);
    }
}
```

**Запрещено в Application:**

- Аннотации JPA (`@Entity`, `@Table`)
- HTTP-специфичные классы (`HttpServletRequest`, `ResponseEntity`)
- Прямые вызовы Spring Data репозиториев
- Знание о конкретных технологиях (Kafka, RabbitMQ, PostgreSQL)
- Ключевое слово `var` — все переменные с явным указанием типа

#### Infrastructure

```java
// ✅ Outbound-адаптер
@Repository
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements OrderPersistencePort {

    private final OrderJpaRepository jpaRepository;
    private final OrderPersistenceMapper mapper;

    @Override
    public Optional<Order> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toModel);
    }

    @Override
    public Order save(Order order) {
        OrderJpaEntity entity = mapper.toEntity(order);
        OrderJpaEntity saved = jpaRepository.save(entity);
        return mapper.toModel(saved);
    }
}
```

```java
// ✅ Inbound-адаптер
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final OrderRestMapper mapper;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = mapper.toCommand(request);
        OrderResult result = createOrderUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(result));
    }
}
```

#### Bootstrap

```java
@Configuration
public class BeanConfig {

    @Bean
    public CreateOrderUseCase createOrderUseCase(
            OrderPersistencePort persistencePort,
            PaymentPort paymentPort) {
        return new CreateOrderService(persistencePort, paymentPort);
    }

    @Bean
    public CancelOrderUseCase cancelOrderUseCase(
            OrderPersistencePort persistencePort) {
        return new CancelOrderService(persistencePort);
    }
}
```

### 2.6. Правила маппинга

В варианте без DDD точек маппинга меньше:

| Граница | Маппер | Что маппит |
|---|---|---|
| REST ↔ Application | `OrderRestMapper` | `CreateOrderRequest` → `CreateOrderCommand`, `OrderResult` → `OrderResponse` |
| Persistence ↔ Application | `OrderPersistenceMapper` | `Order` ↔ `OrderJpaEntity` |

---

## Часть 3. Гексагональная архитектура с CQRS

### 3.1. Обзор

CQRS (Command Query Responsibility Segregation) разделяет модель на две стороны:
**Write Side** (команды, изменяющие состояние) и **Read Side** (запросы, возвращающие данные).
Каждая сторона имеет собственные модели, порты, адаптеры и, при необходимости, отдельные
хранилища данных. Это позволяет независимо оптимизировать запись и чтение: нормализованная
модель для write, денормализованные проекции и View-модели для read.

CQRS может сочетаться как с DDD (богатая доменная модель на write side), так и без DDD
(анемичная модель). Данный раздел описывает структуру CQRS поверх гексагональной архитектуры
с DDD на стороне записи.

### 3.2. Слои и их ответственность

| Слой | Ответственность | Зависимости |
|---|---|---|
| **Domain** | Бизнес-правила, агрегаты, Domain Events (только Write Side) | Ни от чего (чистая Java) |
| **Application** | Порты, Command Handlers, Query Handlers, синхронизация проекций | Зависит от Domain |
| **Infrastructure** | Адаптеры записи и чтения, проекции, event listeners | Зависит от Application |
| **Bootstrap** | Конфигурация, DI wiring, профили среды | Зависит от всех слоёв |

### 3.3. Структура модулей и каталогов

```
order-service/                                  # Root project
├── pom.xml                                     # Parent POM
│
├── order-domain/                               # Модуль Domain — только Write Side
│   ├── pom.xml                                 # Зависимости: ничего
│   └── src/main/java/
│       └── com/company/order/domain/
│           ├── model/
│           │   └── order/
│           │       ├── Order.java              # Aggregate Root
│           │       ├── OrderId.java            # Value Object — UUIDv7
│           │       ├── OrderStatus.java
│           │       ├── OrderLine.java
│           │       └── Money.java
│           ├── event/                          # Domain Events — ключевой элемент CQRS
│           │   ├── DomainEvent.java
│           │   ├── OrderCreatedEvent.java
│           │   ├── OrderConfirmedEvent.java
│           │   ├── OrderCancelledEvent.java
│           │   └── OrderLineAddedEvent.java
│           ├── service/
│           │   └── OrderPricingService.java
│           └── exception/
│               ├── DomainException.java
│               └── OrderNotFoundException.java
│
├── order-application/                          # Модуль Application — команды и запросы
│   ├── pom.xml                                 # Зависимости: order-domain
│   └── src/main/java/
│       └── com/company/order/application/
│           ├── port/
│           │   ├── in/
│           │   │   ├── command/                # Входящие порты — команды (Write)
│           │   │   │   ├── CreateOrderUseCase.java
│           │   │   │   ├── ConfirmOrderUseCase.java
│           │   │   │   └── CancelOrderUseCase.java
│           │   │   └── query/                  # Входящие порты — запросы (Read)
│           │   │       ├── GetOrderDetailsQuery.java
│           │   │       ├── GetOrderSummaryListQuery.java
│           │   │       └── GetOrderStatisticsQuery.java
│           │   └── out/
│           │       ├── write/                  # Исходящие порты — запись
│           │       │   ├── OrderRepository.java
│           │       │   ├── PaymentGateway.java
│           │       │   └── EventPublisher.java
│           │       └── read/                   # Исходящие порты — чтение
│           │           ├── OrderReadRepository.java
│           │           └── OrderStatisticsRepository.java
│           ├── command/                        # Command Handlers (Write Side)
│           │   ├── CreateOrderCommandHandler.java
│           │   ├── ConfirmOrderCommandHandler.java
│           │   └── CancelOrderCommandHandler.java
│           ├── query/                          # Query Handlers (Read Side)
│           │   ├── GetOrderDetailsQueryHandler.java
│           │   ├── GetOrderSummaryListQueryHandler.java
│           │   └── GetOrderStatisticsQueryHandler.java
│           ├── dto/
│           │   ├── command/                    # Объекты команд
│           │   │   ├── CreateOrderCommand.java
│           │   │   ├── ConfirmOrderCommand.java
│           │   │   └── CancelOrderCommand.java
│           │   ├── query/                      # Объекты запросов
│           │   │   ├── OrderDetailsCriteria.java
│           │   │   ├── OrderListCriteria.java
│           │   │   └── OrderStatisticsCriteria.java
│           │   └── result/
│           │       ├── write/                  # Результаты команд
│           │       │   └── OrderCommandResult.java
│           │       └── read/                   # Read Models (View Models)
│           │           ├── OrderDetailsView.java
│           │           ├── OrderSummaryView.java
│           │           └── OrderStatisticsView.java
│           └── mapper/
│               └── OrderApplicationMapper.java
│
├── order-infrastructure/                       # Модуль Infrastructure — адаптеры
│   ├── pom.xml                                 # Зависимости: order-application, spring-*
│   └── src/
│       ├── main/java/
│       │   └── com/company/order/infrastructure/
│       │       ├── in/
│       │       │   ├── rest/
│       │       │   │   ├── OrderCommandController.java    # Endpoint'ы команд (POST/PUT/DELETE)
│       │       │   │   ├── OrderQueryController.java      # Endpoint'ы запросов (GET)
│       │       │   │   ├── request/
│       │       │   │   │   ├── CreateOrderRequest.java
│       │       │   │   │   └── ConfirmOrderRequest.java
│       │       │   │   ├── response/
│       │       │   │   │   ├── OrderDetailsResponse.java
│       │       │   │   │   ├── OrderSummaryResponse.java
│       │       │   │   │   └── OrderStatisticsResponse.java
│       │       │   │   ├── mapper/
│       │       │   │   │   ├── OrderCommandRestMapper.java
│       │       │   │   │   └── OrderQueryRestMapper.java
│       │       │   │   └── handler/
│       │       │   │       └── OrderExceptionHandler.java
│       │       │   └── messaging/
│       │       │       └── OrderCommandListener.java      # Команды через очередь
│       │       ├── out/
│       │       │   ├── write/                              # Адаптеры записи
│       │       │   │   ├── persistence/
│       │       │   │   │   ├── OrderWritePersistenceAdapter.java
│       │       │   │   │   ├── OrderJpaRepository.java
│       │       │   │   │   ├── entity/
│       │       │   │   │   │   ├── OrderJpaEntity.java
│       │       │   │   │   │   └── OrderLineJpaEntity.java
│       │       │   │   │   └── mapper/
│       │       │   │   │       └── OrderWritePersistenceMapper.java
│       │       │   │   ├── payment/
│       │       │   │   │   └── PaymentGatewayAdapter.java
│       │       │   │   └── event/
│       │       │   │       └── SpringEventPublisher.java
│       │       │   └── read/                               # Адаптеры чтения
│       │       │       ├── persistence/
│       │       │       │   ├── OrderReadPersistenceAdapter.java
│       │       │       │   ├── OrderReadJpaRepository.java   # Оптимизированные read-запросы
│       │       │       │   ├── entity/
│       │       │       │   │   └── OrderReadEntity.java      # Денормализованная read-модель
│       │       │       │   └── mapper/
│       │       │       │       └── OrderReadPersistenceMapper.java
│       │       │       └── statistics/
│       │       │           ├── OrderStatisticsAdapter.java
│       │       │           └── OrderStatisticsJpaRepository.java
│       │       ├── projection/                             # Обновление read-моделей
│       │       │   ├── OrderProjectionUpdater.java         # Слушает Domain Events
│       │       │   └── OrderStatisticsUpdater.java
│       │       └── config/
│       │           ├── JpaConfig.java
│       │           ├── KafkaConfig.java
│       │           └── ReadDataSourceConfig.java           # Отдельный DataSource для read
│       └── main/resources/
│           └── db/migration/
│               ├── V001__create_orders_write.sql
│               ├── V002__create_order_lines_write.sql
│               ├── V003__create_orders_read_view.sql       # Денормализованная таблица
│               └── V004__create_order_statistics.sql
│
└── order-bootstrap/                            # Модуль Bootstrap
    ├── pom.xml                                 # Зависимости: order-infrastructure
    └── src/
        ├── main/java/
        │   └── com/company/order/bootstrap/
        │       ├── Application.java
        │       └── config/
        │           ├── BeanConfig.java
        │           ├── SecurityConfig.java
        │           └── SwaggerConfig.java
        └── main/resources/
            ├── application.yml
            └── application-dev.yml
```

### 3.4. Правило зависимостей

```
Bootstrap → Infrastructure → Application → Domain
```

Идентично DDD-варианту. Ключевое отличие — внутри каждого слоя происходит явное разделение
на write и read стороны. Domain существует только на стороне write.

### 3.5. Принцип разделения Write / Read

```
┌─────────────────────────────────────────────────────────────────┐
│                        Bootstrap                                │
├──────────────────────────┬──────────────────────────────────────┤
│      Write Side          │           Read Side                  │
├──────────────────────────┼──────────────────────────────────────┤
│  Controller (POST/PUT)   │   Controller (GET)                   │
│         ↓                │          ↓                           │
│  Command Handler         │   Query Handler                      │
│         ↓                │          ↓                           │
│  Domain Model            │   Read Model (View)                  │
│         ↓                │          ↑                           │
│  Write Repository        │   Read Repository                    │
│         ↓                │          ↑                           │
│  Write DB (normalized)   │   Read DB / View (denormalized)      │
│         │                │          ↑                           │
│         └── Domain Events ──→ Projection Updater ──┘            │
└─────────────────────────────────────────────────────────────────┘
```

Domain Events обеспечивают eventual consistency между write и read моделями.
Projection Updater слушает события и обновляет денормализованные read-таблицы.

### 3.6. Правила кодирования по слоям

#### Domain (только Write Side)

Полностью идентичен Domain из Части 1 (DDD). Агрегаты генерируют Domain Events при каждом
изменении состояния — это критически важно для синхронизации read-модели.

```java
// ✅ Aggregate Root — обязательно генерирует события при изменении
public class Order {

    private OrderId id;
    private CustomerId customerId;
    private List<OrderLine> lines;
    private OrderStatus status;
    private Money totalPrice;
    private List<DomainEvent> domainEvents = new ArrayList<>();

    public static Order create(CustomerId customerId, List<OrderLine> lines) {
        Order order = new Order();
        order.id = OrderId.generate();
        order.customerId = customerId;
        order.lines = List.copyOf(lines);
        order.status = OrderStatus.CREATED;
        order.totalPrice = order.calculateTotal();
        order.domainEvents.add(new OrderCreatedEvent(
                order.id, order.customerId, order.lines, order.totalPrice, order.status));
        return order;
    }

    public void confirm() {
        if (this.status != OrderStatus.CREATED) {
            throw new DomainException("Only created orders can be confirmed");
        }
        this.status = OrderStatus.CONFIRMED;
        this.domainEvents.add(new OrderConfirmedEvent(this.id, this.status));
    }

    public void cancel() {
        if (this.status == OrderStatus.SHIPPED) {
            throw new DomainException("Cannot cancel shipped order");
        }
        this.status = OrderStatus.CANCELLED;
        this.domainEvents.add(new OrderCancelledEvent(this.id, this.status));
    }

    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    public void clearEvents() {
        domainEvents.clear();
    }
}
```

#### Application — порты

```java
// ✅ Входящий порт — команда (Write)
public interface CreateOrderUseCase {
    OrderCommandResult execute(CreateOrderCommand command);
}

// ✅ Входящий порт — запрос (Read)
public interface GetOrderDetailsQuery {
    OrderDetailsView execute(OrderDetailsCriteria criteria);
}

// ✅ Входящий порт — запрос списка (Read)
public interface GetOrderSummaryListQuery {
    List<OrderSummaryView> execute(OrderListCriteria criteria);
}
```

```java
// ✅ Исходящий порт — запись
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    void save(Order order);
}

// ✅ Исходящий порт — чтение (работает с View Models, не с доменными сущностями)
public interface OrderReadRepository {
    Optional<OrderDetailsView> findDetailsById(UUID orderId);
    List<OrderSummaryView> findSummaries(OrderListCriteria criteria);
}

// ✅ Исходящий порт — статистика (Read)
public interface OrderStatisticsRepository {
    OrderStatisticsView getStatistics(OrderStatisticsCriteria criteria);
}
```

#### Application — Command Handler (Write Side)

```java
// ✅ Command Handler — изменяет состояние, публикует события
@RequiredArgsConstructor
public class CreateOrderCommandHandler implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final EventPublisher eventPublisher;
    private final OrderApplicationMapper mapper;

    @Override
    @Transactional
    public OrderCommandResult execute(CreateOrderCommand command) {
        CustomerId customerId = new CustomerId(command.customerId());
        List<OrderLine> lines = mapper.toOrderLines(command.items());

        Order order = Order.create(customerId, lines);

        paymentGateway.authorize(order.totalPrice(), customerId);
        orderRepository.save(order);

        // Публикация Domain Events для обновления read-модели
        order.domainEvents().forEach(eventPublisher::publish);
        order.clearEvents();

        return new OrderCommandResult(order.id().value(), order.status().name());
    }
}
```

#### Application — Query Handler (Read Side)

```java
// ✅ Query Handler — только чтение, работает с View Models
// Не зависит от Domain-модели, не вызывает OrderRepository
@RequiredArgsConstructor
public class GetOrderDetailsQueryHandler implements GetOrderDetailsQuery {

    private final OrderReadRepository readRepository;

    @Override
    @Transactional(readOnly = true)
    public OrderDetailsView execute(OrderDetailsCriteria criteria) {
        return readRepository.findDetailsById(criteria.orderId())
                .orElseThrow(() -> new OrderNotFoundException(criteria.orderId()));
    }
}
```

```java
// ✅ Query Handler — список с фильтрацией и пагинацией
@RequiredArgsConstructor
public class GetOrderSummaryListQueryHandler implements GetOrderSummaryListQuery {

    private final OrderReadRepository readRepository;

    @Override
    @Transactional(readOnly = true)
    public List<OrderSummaryView> execute(OrderListCriteria criteria) {
        return readRepository.findSummaries(criteria);
    }
}
```

#### Application — Read Models (View Models)

```java
// ✅ Read Model — денормализованная модель, оптимизированная для отображения
// Не содержит бизнес-логики, не является доменной сущностью
public record OrderDetailsView(
    UUID orderId,
    String customerName,             // Денормализовано из Customer
    String customerEmail,
    List<OrderLineView> lines,
    String status,
    BigDecimal totalPrice,
    Instant createdAt,
    Instant updatedAt
) {}

public record OrderLineView(
    String productName,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal
) {}

// ✅ Облегчённая модель для списка
public record OrderSummaryView(
    UUID orderId,
    String customerName,
    String status,
    BigDecimal totalPrice,
    int itemCount,
    Instant createdAt
) {}

// ✅ Агрегированная статистика
public record OrderStatisticsView(
    long totalOrders,
    long activeOrders,
    long cancelledOrders,
    BigDecimal totalRevenue,
    BigDecimal averageOrderValue
) {}
```

#### Infrastructure — контроллеры (разделение Command / Query)

```java
// ✅ Command Controller — только мутирующие операции
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderCommandController {

    private final CreateOrderUseCase createOrderUseCase;
    private final ConfirmOrderUseCase confirmOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final OrderCommandRestMapper mapper;

    @PostMapping
    public ResponseEntity<OrderCommandResponse> create(
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = mapper.toCommand(request);
        OrderCommandResult result = createOrderUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(result));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<OrderCommandResponse> confirm(@PathVariable UUID orderId) {
        ConfirmOrderCommand command = new ConfirmOrderCommand(orderId);
        OrderCommandResult result = confirmOrderUseCase.execute(command);
        return ResponseEntity.ok(mapper.toResponse(result));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderCommandResponse> cancel(@PathVariable UUID orderId) {
        CancelOrderCommand command = new CancelOrderCommand(orderId);
        OrderCommandResult result = cancelOrderUseCase.execute(command);
        return ResponseEntity.ok(mapper.toResponse(result));
    }
}
```

```java
// ✅ Query Controller — только чтение
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderQueryController {

    private final GetOrderDetailsQuery getOrderDetailsQuery;
    private final GetOrderSummaryListQuery getOrderSummaryListQuery;
    private final GetOrderStatisticsQuery getOrderStatisticsQuery;
    private final OrderQueryRestMapper mapper;

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetailsResponse> getDetails(@PathVariable UUID orderId) {
        OrderDetailsCriteria criteria = new OrderDetailsCriteria(orderId);
        OrderDetailsView view = getOrderDetailsQuery.execute(criteria);
        return ResponseEntity.ok(mapper.toDetailsResponse(view));
    }

    @GetMapping
    public ResponseEntity<List<OrderSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        OrderListCriteria criteria = new OrderListCriteria(status, page, size);
        List<OrderSummaryView> views = getOrderSummaryListQuery.execute(criteria);
        return ResponseEntity.ok(mapper.toSummaryResponses(views));
    }

    @GetMapping("/statistics")
    public ResponseEntity<OrderStatisticsResponse> statistics(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        OrderStatisticsCriteria criteria = new OrderStatisticsCriteria(from, to);
        OrderStatisticsView view = getOrderStatisticsQuery.execute(criteria);
        return ResponseEntity.ok(mapper.toStatisticsResponse(view));
    }
}
```

#### Infrastructure — Projection Updater

```java
// ✅ Projection Updater — слушает Domain Events и обновляет read-модель
@Component
@RequiredArgsConstructor
public class OrderProjectionUpdater {

    private final OrderReadJpaRepository readRepository;

    @TransactionalEventListener
    public void on(OrderCreatedEvent event) {
        OrderReadEntity readEntity = new OrderReadEntity();
        readEntity.setOrderId(event.orderId().value());
        readEntity.setCustomerName(event.customerName());
        readEntity.setStatus(event.status().name());
        readEntity.setTotalPrice(event.totalPrice().amount());
        readEntity.setItemCount(event.lines().size());
        readEntity.setCreatedAt(event.occurredAt());
        readEntity.setUpdatedAt(event.occurredAt());
        readRepository.save(readEntity);
    }

    @TransactionalEventListener
    public void on(OrderConfirmedEvent event) {
        OrderReadEntity readEntity = readRepository.findByOrderId(event.orderId().value())
                .orElseThrow();
        readEntity.setStatus(event.status().name());
        readEntity.setUpdatedAt(event.occurredAt());
        readRepository.save(readEntity);
    }

    @TransactionalEventListener
    public void on(OrderCancelledEvent event) {
        OrderReadEntity readEntity = readRepository.findByOrderId(event.orderId().value())
                .orElseThrow();
        readEntity.setStatus(event.status().name());
        readEntity.setUpdatedAt(event.occurredAt());
        readRepository.save(readEntity);
    }
}
```

#### Infrastructure — Read Entity (денормализованная)

```java
// ✅ Read Entity — денормализованная, оптимизированная для запросов
// Отличается от Write Entity структурой и набором полей
@Entity
@Table(name = "orders_read_view")
@Getter
@Setter
@NoArgsConstructor
public class OrderReadEntity {

    @Id
    private UUID orderId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    private String status;

    @Column(name = "total_price")
    private BigDecimal totalPrice;

    @Column(name = "item_count")
    private int itemCount;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

**Запрещено в CQRS-архитектуре:**

- Использование Write Repository в Query Handlers (нарушает разделение)
- Чтение доменных агрегатов для отображения данных — для этого существуют View Models
- Прямое обновление read-модели из Command Handler (должно идти через Domain Events)
- Бизнес-логика в Query Handlers — они только читают и маппят
- Мутирующие операции в Query Controller (GET не должен менять состояние)
- Ключевое слово `var` — все переменные с явным указанием типа

### 3.7. Правила маппинга

В CQRS-варианте маппинг разделён по сторонам:

| Граница | Маппер | Что маппит |
|---|---|---|
| REST Command → Application | `OrderCommandRestMapper` | `CreateOrderRequest` → `CreateOrderCommand` |
| REST Query ← Application | `OrderQueryRestMapper` | `OrderDetailsView` → `OrderDetailsResponse` |
| Application → Domain (Write) | `OrderApplicationMapper` | `CreateOrderCommand` → доменные объекты |
| Domain ↔ Write Persistence | `OrderWritePersistenceMapper` | `Order` ↔ `OrderJpaEntity` |
| Read Persistence → Application | `OrderReadPersistenceMapper` | `OrderReadEntity` → `OrderDetailsView` |

### 3.8. Варианты синхронизации Write → Read

| Вариант | Описание | Консистентность | Когда использовать |
|---|---|---|---|
| `@TransactionalEventListener` | В той же транзакции через Spring Events | Strong (одна БД) | Одна БД, простые проекции |
| Kafka / RabbitMQ | Domain Events публикуются в очередь | Eventual | Разные БД, высокая нагрузка |
| Change Data Capture (Debezium) | Отслеживание изменений в WAL БД | Eventual | Без изменений в коде, legacy |
| Scheduled rebuild | Периодическая полная перестройка проекций | Eventual (задержка) | Аналитические запросы, отчёты |

---

## Общие принципы

### Правила кодирования

**Запрет на использование `var`.** Во всём проекте запрещено использование ключевого слова `var`
(local variable type inference). Все переменные должны объявляться с явным указанием типа.
Это повышает читаемость кода, упрощает code review и устраняет неоднозначность при работе
с generic-типами и результатами маппинга.

```java
// ❌ Запрещено
var order = orderRepository.findById(orderId);
var lines = mapper.toOrderLines(command.items());

// ✅ Правильно
Optional<Order> order = orderRepository.findById(orderId);
List<OrderLine> lines = mapper.toOrderLines(command.items());
```

**Использование UUIDv7 в качестве идентификаторов.** Для генерации UUID необходимо использовать
версию UUIDv7 (RFC 9562). В отличие от UUIDv4 (полностью случайный), UUIDv7 содержит
временну́ю метку в старших битах, что обеспечивает монотонную сортируемость значений.
Это критически важно для производительности баз данных: B-tree индексы получают
последовательные вставки вместо случайного разброса по страницам, что снижает фрагментацию
индексов и значительно ускоряет операции записи и range-scan запросов.

```java
// ❌ Не рекомендуется — случайный UUID, фрагментирует индексы
UUID id = UUID.randomUUID(); // UUIDv4

// ✅ Рекомендуется — сортируемый, оптимален для B-tree индексов
// Java 21+: встроенная поддержка (JEP по UUID v7 пока не в стандарте)
// Используйте библиотеку: com.fasterxml.uuid:java-uuid-generator
import com.fasterxml.uuid.Generators;

UUID id = Generators.timeBasedEpochGenerator().generate(); // UUIDv7
```

```xml
<!-- Maven-зависимость для генерации UUIDv7 -->
<dependency>
    <groupId>com.fasterxml.uuid</groupId>
    <artifactId>java-uuid-generator</artifactId>
    <version>5.1.0</version>
</dependency>
```

В DDD-варианте генерация UUIDv7 инкапсулируется в Value Object идентификатора:

```java
public record OrderId(UUID value) {

    public static OrderId generate() {
        return new OrderId(Generators.timeBasedEpochGenerator().generate());
    }
}
```

В варианте без DDD генерацию рекомендуется выносить в утилитный класс или использовать напрямую
в Application Service.

### Именование классов

| Тип | Конвенция | Пример |
|---|---|---|
| Входящий порт | `<Action><Entity>UseCase` / `<Action><Entity>Query` | `CreateOrderUseCase`, `GetOrderQuery` |
| Исходящий порт | `<Entity>Repository` (DDD) / `<Entity><Tech>Port` (без DDD) | `OrderRepository`, `OrderPersistencePort` |
| Use Case реализация | `<UseCase>Impl` (DDD) / `<Action><Entity>Service` (без DDD) | `CreateOrderUseCaseImpl`, `CreateOrderService` |
| Inbound-адаптер | `<Entity>Controller`, `<Entity>GrpcService`, `<Entity>EventListener` | `OrderController` |
| Outbound-адаптер | `<Entity><Tech>Adapter` | `OrderPersistenceAdapter`, `PaymentGatewayAdapter` |
| JPA Entity | `<Entity>JpaEntity` | `OrderJpaEntity` |
| Маппер | `<Entity><Layer>Mapper` | `OrderRestMapper`, `OrderPersistenceMapper` |
| Команда | `<Action><Entity>Command` | `CreateOrderCommand` |
| Результат | `<Entity>Result` | `OrderResult` |
| REST Request | `<Action><Entity>Request` | `CreateOrderRequest` |
| REST Response | `<Entity>Response` | `OrderResponse` |

### Тестирование

| Слой | Вид тестов | Инструменты |
|---|---|---|
| Domain (DDD, CQRS) | Unit-тесты | JUnit 5, AssertJ |
| Application (Command Handlers) | Unit-тесты с моками портов | JUnit 5, Mockito |
| Application (Query Handlers) | Unit-тесты с моками read-портов | JUnit 5, Mockito |
| Infrastructure (адаптеры) | Интеграционные тесты | `@DataJpaTest`, `@WebMvcTest`, Testcontainers |
| Infrastructure (проекции, CQRS) | Интеграционные тесты event → read model | `@DataJpaTest`, Spring Events |
| Bootstrap | E2E / Smoke-тесты | `@SpringBootTest`, Testcontainers |

### Сравнение подходов

| Критерий | С DDD | Без DDD | С CQRS |
|---|---|---|---|
| Сложность домена | Высокая | Низкая / средняя | Высокая |
| Модель данных | Богатая | Анемичная (Records/POJO) | Богатая (Write) + View Models (Read) |
| Количество слоёв | 4 | 3 | 4 |
| Количество маппингов | 3 точки | 2 точки | 5 точек (write + read) |
| Порты определены в | `application.port.in` / `application.port.out` | `application.port.in` / `application.port.out` | `application.port.in.command/query` / `application.port.out.write/read` |
| Бизнес-логика в | Domain Entities + Domain Services | Application Services | Domain Entities (Write Side) |
| Хранилище данных | Одно | Одно | Одно или раздельные (Write DB / Read DB) |
| Контроллеры | Один на агрегат | Один на агрегат | Раздельные (Command / Query) |
| Когда использовать | Complex business rules | CRUD-сервисы, интеграции | Разная нагрузка на чтение/запись, сложные проекции |
| Модульность (Maven) | 4 модуля | 3 модуля | 4 модуля |

### Антипаттерны

| Антипаттерн | Почему плохо | Как правильно |
|---|---|---|
| Использование `var` | Снижает читаемость, скрывает типы при review | Явное указание типа переменной |
| `UUID.randomUUID()` (UUIDv4) как PK | Фрагментация B-tree индексов, деградация записи | UUIDv7 через `Generators.timeBasedEpochGenerator()` |
| JPA-аннотации в доменной модели | Связывает домен с инфраструктурой | Отдельные JPA Entity + маппер |
| Бизнес-логика в контроллере | Нарушает разделение ответственности | Логика в Application/Domain Service |
| Прямой вызов репозитория из контроллера | Пропуск слоя Application, нет оркестрации | Контроллер → Use Case → Port |
| `@Service` на доменном классе (DDD) | Привязка домена к Spring | Wiring в Bootstrap через `@Bean` |
| Один маппер на все слои | Нарушает изоляцию, утечка абстракций | Отдельный маппер на каждой границе |
| Return JPA Entity из контроллера | Утечка инфраструктуры наружу | Маппинг в Response DTO |
| Общий пакет `shared`/`common` | Нарушает модульность | Дублирование допустимо, или отдельный модуль `shared-kernel` |
| Write Repository в Query Handler (CQRS) | Нарушает разделение Write/Read, нет оптимизации чтения | Read Repository + View Models |
| Чтение агрегата для отображения (CQRS) | Загрузка тяжёлого графа объектов для простого GET | Денормализованная read-модель |
| Обновление read-модели напрямую из Command Handler (CQRS) | Связывает write и read, усложняет масштабирование | Синхронизация через Domain Events |
| Один контроллер для команд и запросов (CQRS) | Смешивает ответственности, затрудняет масштабирование | Раздельные Command/Query контроллеры |
