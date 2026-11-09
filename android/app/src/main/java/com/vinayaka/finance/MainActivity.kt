package com.vinayaka.finance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.vinayaka.finance.ui.AppRoot
import com.vinayaka.finance.ui.AppViewModel

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val auth get() = (application as VinayakaApp).container.auth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Google's "allow access to Sheets" screen comes back through this launcher.
        val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            auth.onConsentResult(r.data, r.resultCode == RESULT_OK)
        }
        auth.attach(this, consent)
        setContent { AppRoot(vm) }
    }

    override fun onDestroy() {
        auth.detach(this)
        super.onDestroy()
    }
}
