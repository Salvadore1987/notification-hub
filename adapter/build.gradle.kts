// Слой adapter целиком: агрегатор над модулями адаптеров (AR-01, AR-04).
//
// Своего кода здесь нет — каждый адаптер живёт в своём модуле под in/, out/ и out/provider/.
// Этот проект существует ради двух вещей: `./gradlew :adapter:build` собирает и проверяет слой
// целиком, а bootstrap зависит от одной строки вместо двадцати одной — ему по определению нужны
// все адаптеры сразу, он и есть композиционный корень.
//
// api, а не implementation: зависимость транзитивна для bootstrap'а, который пишет wiring против
// классов адаптеров (WebSecurityConfig, health-индикаторы, DomainServiceConfig).
//
// Между собой адаптеры зависят напрямую и явно (например, in:admin -> in:rest ради тела §8.2):
// этот агрегатор их связи не заменяет и не ослабляет.

dependencies {
    api(project(":adapter:in:admin"))
    api(project(":adapter:in:callback"))
    api(project(":adapter:in:contract"))
    api(project(":adapter:in:importer"))
    api(project(":adapter:in:kafka"))
    api(project(":adapter:in:rest"))
    api(project(":adapter:in:scheduler"))
    api(project(":adapter:in:security"))

    api(project(":adapter:out:compliance"))
    api(project(":adapter:out:kafka"))
    api(project(":adapter:out:metrics"))
    api(project(":adapter:out:persistence"))
    api(project(":adapter:out:policy"))
    api(project(":adapter:out:secret"))
    api(project(":adapter:out:time"))

    api(project(":adapter:out:provider:apns"))
    api(project(":adapter:out:provider:fcm"))
    api(project(":adapter:out:provider:playmobile"))
    api(project(":adapter:out:provider:smsgate"))
    api(project(":adapter:out:provider:smtp"))
    api(project(":adapter:out:provider:support"))

    api(project(":adapter:observability"))
}
