package com.github.jvsena42.mandacaru.presentation.ui.screens.coinjoin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.jvsena42.mandacaru.domain.coinjoin.PoolContent
import com.github.jvsena42.mandacaru.presentation.ui.theme.MandacaruTheme
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScreenCoinjoin(
    bottomContentPadding: Dp = 0.dp,
    viewModel: CoinjoinViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    ScreenCoinjoinContent(
        uiState = uiState,
        onAction = viewModel::onAction,
        bottomContentPadding = bottomContentPadding,
    )
}

@Composable
fun ScreenCoinjoinContent(
    uiState: CoinjoinUiState,
    onAction: (CoinjoinAction) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val snackBarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnAction by rememberUpdatedState(onAction)

    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage.isNotEmpty()) {
            scope.launch {
                snackBarHostState.showSnackbar(message = uiState.errorMessage)
                currentOnAction(CoinjoinAction.ClearSnackBarMessage)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(hostState = snackBarHostState, modifier = Modifier.padding(bottom = bottomContentPadding))
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { currentOnAction(CoinjoinAction.OnClickCreatePool) },
                modifier = Modifier.testTag("button_create_pool").padding(bottom = bottomContentPadding),
                text = { Text("New pool") },
                icon = {},
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxWidth()) {
            if (uiState.activePoolStatus.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        uiState.activePoolStatus,
                        modifier = Modifier.padding(16.dp).testTag("coinjoin_active_status"),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag("coinjoin_pool_list"),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomContentPadding + 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.pools, key = { it.id }) { pool ->
                    PoolCard(pool = pool, onClickJoin = { currentOnAction(CoinjoinAction.OnClickJoinPool(pool)) })
                }
            }
        }
    }

    if (uiState.isCreateDialogVisible) {
        CreatePoolDialog(
            denominationInput = uiState.denominationSatsInput,
            onDenominationChanged = { currentOnAction(CoinjoinAction.OnDenominationChanged(it)) },
            onConfirm = { currentOnAction(CoinjoinAction.OnConfirmCreatePool) },
            onDismiss = { currentOnAction(CoinjoinAction.OnDismissCreateDialog) },
        )
    }
}

@Composable
private fun PoolCard(pool: PoolContent, onClickJoin: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("pool_item_${pool.id}")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${pool.denominationSats} sats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${pool.peers} peers · fee ${pool.feeRateSatVb} sat/vB", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClickJoin, modifier = Modifier.testTag("button_join_${pool.id}")) {
                Text("Join")
            }
        }
    }
}

@Composable
private fun CreatePoolDialog(
    denominationInput: String,
    onDenominationChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a pool") },
        text = {
            OutlinedTextField(
                value = denominationInput,
                onValueChange = onDenominationChanged,
                label = { Text("Denomination (sats)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.testTag("input_denomination"),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("button_confirm_create_pool")) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@PreviewLightDark
@Composable
private fun Preview() {
    MandacaruTheme {
        Surface {
            ScreenCoinjoinContent(
                uiState = CoinjoinUiState(
                    pools = listOf(
                        PoolContent(
                            id = "abc123",
                            publicKey = "pub",
                            denominationSats = 100_000,
                            peers = 5,
                            timeoutSeconds = 3600,
                            relay = "wss://relay.damus.io",
                            feeRateSatVb = 1.0,
                        ),
                    ),
                ),
                onAction = {},
            )
        }
    }
}
