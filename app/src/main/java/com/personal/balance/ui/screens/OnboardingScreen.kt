package com.personal.balance.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.ThemeMode
import com.personal.balance.ui.components.ThemeToggle
import com.personal.balance.viewmodel.OnboardingViewModel
import com.personal.balance.viewmodel.OnboardingNavigationEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val navigationEvent by viewModel.navigationEvent.collectAsState()

    // Load onboarding state on first composition
    LaunchedEffect(Unit) {
        viewModel.loadOnboardingState()
    }

    LaunchedEffect(navigationEvent) {
        when (navigationEvent) {
            OnboardingNavigationEvent.NavigateToDashboard -> {
                viewModel.clearNavigationEvent()
                onNavigateToDashboard()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.verificationRequired) "Verify Email" else "Setup") },
                actions = {
                    ThemeToggle(
                        currentTheme = themeMode,
                        onToggle = onToggleTheme
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .widthIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.verificationRequired) {
                    // VERIFICATION GATE: an Email/Password account whose address is
                    // not verified is held here. Google authentication is not offered
                    // on this screen, and the app will refuse an unauthenticated
                    // Google sign-in/up for this email on this device, so the
                    // password provider can never be overwritten.
                    Text(
                        text = "Verify Your Email",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "Your email address must be confirmed before you can use this account with Google sign-in. This keeps your email/password login safe.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (uiState.verificationMessage != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = uiState.verificationMessage ?: "",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Button(
                        onClick = viewModel::refreshVerificationState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("I've Verified - Continue")
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::resendVerificationEmail,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = !uiState.isLoading
                    ) {
                        Text("Resend Email")
                    }

                    uiState.verificationEmail?.let { email ->
                        Text(
                            text = "Sent to $email. Check your inbox (and spam folder).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                } else if (uiState.isOnboardingCompleted) {
                    // Show current balance for returning users
                    Text(
                        text = "Welcome Back",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "Your current balance is:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = EGPFormatter.formatAmount(uiState.currentBalance),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.completeOnboarding() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Continue to Dashboard")
                        }
                    }
                } else {
                    // Header
                    Text(
                        text = "Let's Get Started",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "Set up your initial balance in EGP",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Balance Input
                    OutlinedTextField(
                        value = uiState.balanceInput,
                        onValueChange = viewModel::updateBalanceInput,
                        label = { Text("Initial Balance (EGP)") },
                        placeholder = { Text("0.00") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        isError = uiState.balanceError != null,
                        supportingText = {
                            if (uiState.balanceError != null) {
                                Text(
                                    text = uiState.balanceError ?: "",
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text("Enter your total available balance")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isLoading
                    )

                    // General Error
                    if (uiState.generalError != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = uiState.generalError ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Submit Button
                    Button(
                        onClick = viewModel::completeOnboarding,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Continue to Dashboard")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}