package com.vinayaka.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinayaka.finance.data.Prefs
import com.vinayaka.finance.ui.AppViewModel
import com.vinayaka.finance.ui.components.EmptyState

/** Shown right after sign-in while the Users tab is read to see whether this account may use the app. */
@Composable
fun CheckingAccessScreen(vm: AppViewModel, prefs: Prefs) {
    val error by vm.accessError.collectAsStateWithLifecycle()
    LaunchedEffect(prefs.email) { vm.checkAccess() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (error == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text("Checking your access…", style = MaterialTheme.typography.titleMedium)
            Text(prefs.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Couldn't check your access", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = vm::checkAccess, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Use a different Google account") }
        }
    }
}

/** This Google account is not on the Users list (or cannot open the Sheet), so the app stays locked. */
@Composable
fun NoAccessScreen(vm: AppViewModel, prefs: Prefs) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f)) {
            EmptyState(Icons.Rounded.Lock, "No access", prefs.deniedMessage)
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::checkAccess, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Check again") }
            OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Use a different Google account") }
        }
    }
}
