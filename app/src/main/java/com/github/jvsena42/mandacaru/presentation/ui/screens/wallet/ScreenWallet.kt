package com.github.jvsena42.mandacaru.presentation.ui.screens.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.jvsena42.mandacaru.presentation.ui.theme.MandacaruTheme
import com.github.jvsena42.mandacaru.presentation.utils.encodeQr
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScreenWallet(
    bottomContentPadding: Dp = 0.dp,
    viewModel: WalletViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    ScreenWalletContent(
        uiState = uiState,
        onAction = viewModel::onAction,
        bottomContentPadding = bottomContentPadding,
    )
}

@Composable
fun ScreenWalletContent(
    uiState: WalletUiState,
    onAction: (WalletAction) -> Unit,
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
                currentOnAction(WalletAction.ClearSnackBarMessage)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(hostState = snackBarHostState, modifier = Modifier.padding(bottom = bottomContentPadding))
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                bottom = bottomContentPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BalanceCard(uiState.balanceSats) }
            item {
                ReceiveCard(
                    address = uiState.receiveAddress,
                    onClickNewAddress = { currentOnAction(WalletAction.OnClickNewAddress) },
                )
            }
            item { BackupCard(onClickReveal = { currentOnAction(WalletAction.OnClickRevealSeed) }) }
        }
    }

    if (uiState.isSeedDialogVisible) {
        SeedBackupDialog(
            seedPhrase = uiState.seedPhrase,
            onDismiss = { currentOnAction(WalletAction.OnDismissSeedDialog) },
        )
    }
}

@Composable
private fun BalanceCard(balanceSats: Long) {
    Card(modifier = Modifier.fillMaxWidth().testTag("wallet_balance_card")) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Balance", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "$balanceSats sats",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("wallet_balance"),
            )
        }
    }
}

@Composable
private fun ReceiveCard(address: String, onClickNewAddress: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Receive", style = MaterialTheme.typography.labelLarge)
            if (address.isNotEmpty()) {
                val qr = remember(address) { encodeQr(address) }
                qr?.let {
                    Image(bitmap = it, contentDescription = "Receive address QR code", modifier = Modifier.size(200.dp))
                }
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("wallet_receive_address"),
                )
            }
            Button(onClick = onClickNewAddress, modifier = Modifier.testTag("button_new_address")) {
                Text(if (address.isEmpty()) "Generate address" else "New address")
            }
        }
    }
}

@Composable
private fun BackupCard(onClickReveal: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup", style = MaterialTheme.typography.labelLarge)
            Text(
                "This wallet holds real private keys on this device. Back up its seed " +
                    "phrase somewhere safe - anyone with it can spend your coins.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onClickReveal, modifier = Modifier.testTag("button_reveal_seed")) {
                Text("Reveal seed phrase")
            }
        }
    }
}

@Composable
private fun SeedBackupDialog(seedPhrase: String, onDismiss: () -> Unit) {
    val words = remember(seedPhrase) { seedPhrase.split(" ").filter { it.isNotBlank() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your seed phrase") },
        text = {
            Column(modifier = Modifier.testTag("wallet_seed_phrase")) {
                words.forEachIndexed { index, word ->
                    Text("${index + 1}. $word", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@PreviewLightDark
@Composable
private fun Preview() {
    MandacaruTheme {
        Surface {
            ScreenWalletContent(
                uiState = WalletUiState(balanceSats = 150_000, receiveAddress = "bc1qexampleaddress0000000000000"),
                onAction = {},
            )
        }
    }
}
