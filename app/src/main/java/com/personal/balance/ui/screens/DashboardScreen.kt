package com.personal.balance.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.personal.balance.BuildConfig
import com.personal.balance.R
import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.ThemeMode
import com.personal.balance.model.TransactionType
import com.personal.balance.ui.components.CircularTargetProgress
import com.personal.balance.ui.components.ThemeToggle
import com.personal.balance.viewmodel.DashboardInsightUiState
import com.personal.balance.viewmodel.DashboardInsightViewModel
import com.personal.balance.viewmodel.DashboardUiState
import com.personal.balance.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenAiSmokeTest: (() -> Unit)? = null,
    onOpenFinancialAssistant: () -> Unit = {},
    onOpenAllTransactions: () -> Unit = {},
    insightsViewModel: DashboardInsightViewModel? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val insightsVm = insightsViewModel ?: viewModel { DashboardInsightViewModel() }
    val insightState by insightsVm.uiState.collectAsState()
    val insightRefreshing by insightsVm.refreshing.collectAsState()
    var showTargetDialog by remember { mutableStateOf(false) }
    var showTransactionDialog by remember { mutableStateOf<TransactionType?>(null) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showLinkGoogleDialog by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val webClientId = stringResource(R.string.default_web_client_id)

    // The dashboard's AI Insights section loads once when first composed.
    LaunchedEffect(Unit) { insightsVm.ensureLoaded() }

    // Account action feedback (link success/failure, delete errors)
    val userFeedback by viewModel.userFeedback.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(userFeedback) {
        userFeedback?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearUserFeedback()
        }
    }

    // Google Sign-In launcher for linking Google account
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                val idToken = account?.idToken
                if (idToken != null) {
                    viewModel.linkGoogleAccount(idToken)
                }
            } catch (e: com.google.android.gms.common.api.ApiException) {
                // Handle error silently or show toast
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Balance Manager") },
                actions = {
                    ThemeToggle(
                        currentTheme = themeMode,
                        onToggle = onToggleTheme
                    )
                    // AI Financial Assistant — its own shortcut beside the settings gear.
                    IconButton(
                        onClick = onOpenFinancialAssistant
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "AI Financial Assistant",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // AI smoke test — visible in debug builds only.
                    if (BuildConfig.DEBUG && onOpenAiSmokeTest != null) {
                        IconButton(
                            onClick = onOpenAiSmokeTest
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Science,
                                contentDescription = "AI Smoke Test",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Settings gear icon with anchored dropdown
                    Box {
                        IconButton(
                            onClick = { showSettingsMenu = !showSettingsMenu }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                        // Settings dropdown menu anchored to gear icon
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false }
                        ) {
                            // User account info at top
                            val userEmail = viewModel.currentUserEmail ?: "Unknown account"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = userEmail,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Divider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            DropdownMenuItem(
                                onClick = {
                                    showSettingsMenu = false
                                    onSignOut()
                                },
                                text = { Text("Sign Out", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Logout,
                                        contentDescription = "Sign Out",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                            // Link Google Account - only show if user has Email/Password but not Google
                            val dashboardState = uiState
                            if (dashboardState is DashboardUiState.Success && dashboardState.canLinkGoogle) {
                                DropdownMenuItem(
                                    onClick = {
                                        showSettingsMenu = false
                                        showLinkGoogleDialog = true
                                    },
                                    text = { Text("Link Google Account", color = MaterialTheme.colorScheme.primary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Link,
                                            contentDescription = "Link Google Account",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )
                            }
                            DropdownMenuItem(
                                onClick = {
                                    showSettingsMenu = false
                                    showDeleteConfirmation = true
                                },
                                text = { Text("Delete Account", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete Account",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {

            when (val state = uiState) {
                is DashboardUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                is DashboardUiState.Success -> {
                    // Tell the insights section what data it was rendered against;
                    // a differing signature flags the cached analysis as stale.
                    val signature = "${state.userConfig.currentBalance}|${state.transactions.size}|" +
                        "${state.userConfig.targetEnabled}|${state.userConfig.targetAmount}|" +
                        "${state.transactions.maxOfOrNull { it.timestamp }}"
                    LaunchedEffect(signature) { insightsVm.markStaleIfChanged(signature) }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Balance Card
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 600.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Current Balance", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = EGPFormatter.formatAmount(state.userConfig.currentBalance),
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Target Section or Set Target Button
                        if (state.userConfig.targetEnabled) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        val progress = (state.userConfig.currentBalance.toFloat() / state.userConfig.targetAmount.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                                        val percentage = "${(progress * 100).toInt()}%"

                                        CircularTargetProgress(
                                            progress = progress,
                                            percentage = percentage
                                        )

                                        Text(
                                            state.userConfig.targetName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Goal: ${EGPFormatter.formatAmount(state.userConfig.targetAmount)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Button(
                                    onClick = { showTargetDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp)
                                        .height(48.dp)
                                ) {
                                    Text("Set Target")
                                }
                            }
                        }

                        // Celebration Modal
                        if (state.userConfig.targetCompleted && !state.userConfig.targetAcknowledged) {
                            item {
                                AlertDialog(
                                    onDismissRequest = { viewModel.acknowledgeTarget() },
                                    title = { Text("Yay!") },
                                    text = { Text("You've reached your target.") },
                                    confirmButton = {
                                        Button(onClick = { viewModel.acknowledgeTarget() }) {
                                            Text("Dismiss")
                                        }
                                    }
                                )
                            }
                        }

                        // Action Buttons
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 600.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { showTransactionDialog = TransactionType.INCOME },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Income")
                                }
                                Button(
                                    onClick = { showTransactionDialog = TransactionType.EXPENSE },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Remove, null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Expense")
                                }
                            }
                        }

                        // AI Insights Section
                        item {
                            DashboardInsightsSection(
                                state = insightState,
                                refreshing = insightRefreshing,
                                onRefresh = insightsVm::refresh,
                                onOpenAll = onOpenFinancialAssistant,
                            )
                        }

                        // Recent Transactions Header
                        if (state.transactions.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Recent Activity",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onOpenAllTransactions) {
                                        Text("See all")
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    "No transactions yet. Add your first income or expense to get started.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        // Recent Transactions List
                        items(state.transactions) { transaction ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        transaction.description,
                                        maxLines = 1
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        transaction.category.ifBlank { "General" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingContent = {
                                    Text(
                                        (if (transaction.type == TransactionType.INCOME) "+" else "-") + EGPFormatter.formatAmount(transaction.amount),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (transaction.type == TransactionType.INCOME)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }

                is DashboardUiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // Target Dialog
    if (showTargetDialog) {
        var name by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTargetDialog = false },
            title = { Text("Set Target") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Target Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount (EGP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        EGPFormatter.parseInputToPiastres(amount)?.let {
                            viewModel.setTarget(name, it)
                        }
                        showTargetDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTargetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Transaction Dialog
    showTransactionDialog?.let { type ->
        var amount by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var submitted by remember { mutableStateOf(false) }
        val saving by viewModel.transactionSaving.collectAsState()
        // Close once the save (incl. automatic categorization) completes so the
        // dialog cannot be dismissed/duplicated mid-flight.
        LaunchedEffect(saving, submitted) {
            if (submitted && !saving) {
                showTransactionDialog = null
            }
        }
        AlertDialog(
            onDismissRequest = {
                showTransactionDialog = null
            },
            title = { Text("Add ${type.name.lowercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount (EGP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !saving,
                    onClick = {
                        val parsed = EGPFormatter.parseInputToPiastres(amount)
                        if (parsed != null && !submitted) {
                            submitted = true
                            viewModel.addTransaction(type, parsed, description)
                        }
                    }
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Adding...")
                    } else {
                        Text("Add")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTransactionDialog = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Account Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Account") },
            text = { Text("This will permanently delete your account and all associated data. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Link Google Account Confirmation Dialog
    if (showLinkGoogleDialog) {
        AlertDialog(
            onDismissRequest = { showLinkGoogleDialog = false },
            title = { Text("Link Google Account") },
            text = { Text("This will add Google as a second sign-in method to your account. Your Email/Password credentials will remain active. Continue?") },
            confirmButton = {
                Button(
                    onClick = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(webClientId)
                            .requestEmail()
                            .build()
                        val googleSignInClient = GoogleSignIn.getClient(context, gso)
                        // Sign out first to allow account chooser
                        googleSignInClient.signOut().addOnCompleteListener {
                            googleSignInLauncher.launch(googleSignInClient.signInIntent)
                        }
                        showLinkGoogleDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Link Google Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkGoogleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
