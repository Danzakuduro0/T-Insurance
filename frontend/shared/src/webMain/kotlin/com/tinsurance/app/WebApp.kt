package com.tinsurance.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tinsurance.app.admin.AdminScreen
import com.tinsurance.app.client.ClientScreen
import com.tinsurance.app.operator.OperatorScreen
import com.tinsurance.app.staff.StaffPortal
import com.tinsurance.app.ui.InsuranceTheme

@Composable
fun WebApp(fragment: String, navigate: (String) -> Unit) {
    if (fragment.equals("staff", ignoreCase = true)) {
        StaffPortal(onClient = { navigate("") })
        return
    }

    val clientScreen = ClientScreen.entries.firstOrNull { it.name.equals(fragment, ignoreCase = true) }
    val operatorScreen = OperatorScreen.entries.firstOrNull { "Operator${it.name}".equals(fragment, ignoreCase = true) }
    val adminScreen = AdminScreen.entries.firstOrNull { "Admin${it.name}".equals(fragment, ignoreCase = true) }
    val showDemo = fragment.equals("Demo", ignoreCase = true)
    if (clientScreen != null || operatorScreen != null || adminScreen != null || showDemo) {
        App(
            showStatusBar = clientScreen != null,
            initialScreen = clientScreen ?: ClientScreen.Claims,
            initialOperatorScreen = operatorScreen,
            initialAdminScreen = adminScreen,
            showDemo = showDemo,
        )
        return
    }

    InsuranceTheme {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { navigate("staff") }) { Text("Вход для сотрудников") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { App() }
        }
    }
}
