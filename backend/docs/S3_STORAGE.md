# Вложения в Cloud.ru Object Storage

Сервер загружает, читает и удаляет объекты через `FileStorage`. PostgreSQL хранит ключ вида
`claims/{claimId}/{uuid}.{ext}` и метаданные. Клиент и оператор продолжают скачивать вложения
через авторизованные маршруты бэкенда. OpenAPI и коллекция Postman подходят без изменений.

## Конфигурация

Создайте закрытый бакет и сервисный аккаунт. Ключу нужны операции `GetObject`, `PutObject`
и `DeleteObject` для объектов этого бакета. В Cloud.ru задайте роли только на нужный бакет и
ограничьте их политикой доступа до этих операций. Публичный доступ к бакету оставьте выключенным.

Задайте параметры в общем `backend/.env` по шаблону `.env.example`. Docker Compose читает `.env` автоматически.

| Переменная | Значение |
| --- | --- |
| `STORAGE_PROVIDER` | `s3`. Если не задана, используется `local`. |
| `S3_ENDPOINT` | `https://s3.cloud.ru` |
| `S3_REGION` | `ru-central-1` |
| `S3_BUCKET` | Имя закрытого бакета. |
| `S3_ACCESS_KEY_ID` | `<tenant_id>:<key_id>` из Cloud.ru. |
| `S3_SECRET_ACCESS_KEY` | Key Secret сервисного аккаунта. |
| `S3_PATH_STYLE` | `true` для адресации бакета по пути. |
| `S3_TIMEOUT_SECONDS` | Таймаут запроса в секундах. По умолчанию `30`. |
| `S3_CONNECT_TIMEOUT_SECONDS` | Таймаут соединения в секундах. По умолчанию `5`. |
| `POSTGRES_HOST_PORT` | `15432`, если порт `5432` занят. Внутренний порт в Docker остаётся `5432`. |

`S3_ACCESS_KEY_ID` состоит из ID тенанта и ID ключа сервисного аккаунта, разделённых двоеточием
(`<tenant_id>:<key_id>`). В `S3_SECRET_ACCESS_KEY` запишите секретную часть той же пары.
Сервер подписывает ею запросы в бакет. Храните значения в `.env`, который игнорируется Git.
[Ключ для S3 API](https://cloud.ru/docs/s3e/ug/topics/api__api-key) ·
[SDK для Java](https://cloud.ru/docs/s3e/ug/topics/tools__sdk-java) ·
[Политика бакета](https://cloud.ru/docs/s3e/ug/topics/security__bucketpolicy)

Для IntelliJ передайте параметры S3 в окружение конфигурации `ApplicationKt`.
`DB_URL` должен указывать на выбранный порт PostgreSQL.

## Запуск в Docker

В каталоге `backend` выполните команды.

```sh
./gradlew :server:buildFatJar
docker compose --profile app up -d --build
curl -fsS http://127.0.0.1:8080/health
```

Для ранее созданного демоокружения задайте `COMPOSE_PROJECT_NAME=tinsurance-s3` в `.env`.
Compose выберет его контейнеры и том PostgreSQL. При переключении рабочей базы на S3 заранее
скопируйте её вложения в бакет с сохранением ключей из `claim_files.storage_key`.

Встроенная проверка качества фото читает объекты через тот же `FileStorage`. Если включён
внешний `ML_URL`, его сервису нужен доступ на чтение из этого бакета по `storage_key`:
см. [ML_CONTRACT.md](ML_CONTRACT.md).
