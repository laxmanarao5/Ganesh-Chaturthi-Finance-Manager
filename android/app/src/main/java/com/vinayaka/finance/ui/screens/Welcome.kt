package com.vinayaka.finance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CurrencyRupee
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.data.Prefs
import com.vinayaka.finance.ui.AppViewModel

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    val working by vm.working.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.primary, scheme.secondary))).statusBarsPadding(),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CurrencyRupee, null, Modifier.size(52.dp), tint = Color.White)
            }
            Spacer(Modifier.height(24.dp))
            Text("Vinayaka Finance", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                "Festival accounts that work offline and sync through your own Google Sheet.",
                style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
        }
        Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = scheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = vm::signIn, enabled = !working && vm.googleConfigured,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                    else Text("Sign in with Google")
                }
                if (!vm.googleConfigured) {
                    Text(
                        "Google sign-in isn't set up in this build yet. Add your Web client ID to secrets.properties and rebuild. You can still try the app in demo mode.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = vm::startDemo, enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Try demo (no account)")
                }
                error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
fun ConnectSheetScreen(vm: AppViewModel, prefs: Prefs) {
    val working by vm.working.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Connect your Google Sheet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Signed in as ${prefs.email}. Paste the link of the Google Sheet that holds the committee's accounts. " +
                "Each year gets its own tab, created automatically.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Before you connect", style = MaterialTheme.typography.titleMedium)
                Text("1. Create a blank Google Sheet (or open the existing one).", style = MaterialTheme.typography.bodyMedium)
                Text("2. Share it with every committee member's Google account: Editor to add records, Viewer to only look.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Copy the link from the browser and paste it below.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedTextField(
            value = link, onValueChange = { link = it; vm.clearAuthError() },
            label = { Text("Google Sheet link") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.connectSheet(link) }, enabled = link.isNotBlank() && !working,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Connect and download")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = vm::signOut) { Text("Use a different Google account") }
        }
    }
}
