package ru.tinsurance.server.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** JDBC блокирующий — транзакция выполняется вне потоков обработки запросов. */
suspend fun <T> Database.tx(block: JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { transaction(this@tx) { block() } }
