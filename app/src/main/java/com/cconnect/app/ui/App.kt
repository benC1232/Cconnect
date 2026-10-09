package com.cconnect.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cconnect.app.MainViewModel
import com.cconnect.app.Screen

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (state.screen) {
        Screen.Login -> LoginScreen(state, vm)
        Screen.Sessions -> SessionsScreen(state, vm)
        Screen.NewSession -> NewSessionScreen(state, vm)
    }
}
