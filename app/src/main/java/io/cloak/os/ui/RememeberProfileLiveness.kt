package io.cloak.os.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.cloak.os.bridge.Liveness
import io.cloak.os.bridge.SpaceStateReader
import kotlinx.coroutines.delay

private const val POLL_MS = 1_500L

@Composable
fun rememberProfileLiveness(): State<Liveness> {
    val context = LocalContext.current
    val lifecycle = remember(context) { context.findActivity().lifecycle }
    val state = remember { mutableStateOf(SpaceStateReader.readLiveness(context)) }
    var resumed by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    state.value = SpaceStateReader.readLiveness(context)
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        if (resumed) {
            while (true) {
                delay(POLL_MS)
                state.value = SpaceStateReader.readLiveness(context)
            }
        }
    }
    return state
}