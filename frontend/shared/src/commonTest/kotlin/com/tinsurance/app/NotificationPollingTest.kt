package com.tinsurance.app

import com.tinsurance.app.client.ClientViewModel
import com.tinsurance.app.client.mergeNewest
import com.tinsurance.app.data.MemoryStore
import com.tinsurance.app.data.MobileApiClient
import com.tinsurance.app.data.SessionStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.tinsurance.api.mobile.NotificationDto

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPollingTest {
    private fun note(id: Long, readAt: String? = null) =
        NotificationDto(id, 5, "STATUS_CHANGED", "Статус $id", "Статус $id", "2026-11-12T10:0$id:00Z", readAt)

    private fun json(n: NotificationDto) =
        """{"id":${n.id},"claimId":5,"type":"${n.type}","title":"${n.title}","body":"${n.body}",
           "createdAt":"${n.createdAt}","readAt":${n.readAt?.let { "\"$it\"" } ?: "null"}}"""

    /** Сервер, где список уведомлений можно менять между шагами опроса. */
    private class Server {
        var items = listOf<NotificationDto>()
        var claimsRequests = 0
        var down = false
    }

    private fun viewModel(server: Server): ClientViewModel {
        val api =
            MobileApiClient("http://test", SessionStorage(MemoryStore())) { config ->
                HttpClient(
                    MockEngine { request ->
                        if (server.down) throw Error("Fail to fetch")
                        val path = request.url.encodedPath
                        val unreadOnly = request.url.parameters["unreadOnly"] == "true"
                        val body =
                            when {
                                path.endsWith("/auth/login") ->
                                    """{"accessToken":"a","accessTokenExpiresIn":900,"refreshToken":"r",
                                       "user":{"id":2,"role":"CLIENT","fullName":"Анна","phone":"+79000000002","email":null}}"""
                                path.endsWith("/notifications") -> {
                                    val shown = server.items.filter { !unreadOnly || it.readAt == null }
                                    """{"items":[${shown.joinToString { json(it) }}],"nextBeforeId":null}"""
                                }
                                path.endsWith("/claims") -> "[]".also { server.claimsRequests++ }
                                else -> "[]"
                            }
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                    config,
                )
            }
        return ClientViewModel(api).also { it.login("89000000002", "pw") }
    }

    /** Вход и перезагрузка списка идут в фоне ViewModel — ждём результата в реальном времени. */
    private suspend fun awaitUntil(condition: () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!condition()) delay(10) }
        }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun freshPageGoesOnTopAndKeepsOlderLoadedItems() {
        val loaded = listOf(note(3), note(2), note(1))
        val fresh = listOf(note(5), note(4), note(3, readAt = "2026-11-12T11:00:00Z"))
        val merged = mergeNewest(fresh, loaded)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), merged.map { it.id })
        // Отметка «прочитано» пришла со свежей страницы.
        assertNotNull(merged.first { it.id == 3L }.readAt)
    }

    @Test
    fun pollUpdatesBadgeEverywhereAndListOnlyOnNotificationsScreen() = runTest {
        val server = Server()
        val vm = viewModel(server)
        awaitUntil { vm.user != null }
        server.items = listOf(note(1))

        vm.pollNotifications(onNotifications = false, onClaims = false)
        assertEquals(1, vm.notifications.unreadCount)
        assertTrue(vm.notifications.items.isEmpty())

        server.items = listOf(note(2), note(1))
        vm.pollNotifications(onNotifications = true, onClaims = false)
        assertEquals(2, vm.notifications.unreadCount)
        assertEquals(listOf(2L, 1L), vm.notifications.items.map { it.id })
    }

    @Test
    fun newNotificationReloadsClaimsListAndNetworkErrorIsSilent() = runTest {
        val server = Server()
        val vm = viewModel(server)
        awaitUntil { vm.user != null }
        val before = server.claimsRequests

        server.items = listOf(note(1))
        vm.pollNotifications(onNotifications = false, onClaims = true)
        awaitUntil { server.claimsRequests == before + 1 && !vm.claims.loading }

        // Ничего нового — список обращений не трогаем.
        vm.pollNotifications(onNotifications = false, onClaims = true)
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(before + 1, server.claimsRequests)

        // Нет связи: ни ошибки на экране, ни выхода из аккаунта.
        server.down = true
        vm.pollNotifications(onNotifications = true, onClaims = true)
        assertNull(vm.notifications.error)
        assertNotNull(vm.user)
        assertEquals(1, vm.notifications.unreadCount)
    }
}
