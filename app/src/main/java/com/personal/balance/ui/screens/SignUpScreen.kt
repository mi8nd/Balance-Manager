package com.personal.balance.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.personal.balance.R
import com.personal.balance.domain.ThemeMode
import com.personal.balance.ui.components.GoogleSignInButton
import com.personal.balance.ui.components.ThemeToggle
import com.personal.balance.viewmodel.SignUpViewModel
import com.personal.balance.viewmodel.AuthNavigationEvent
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    viewModel: SignUpViewModel,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToOnboarding: () -> Unit,
    onNavigateToDashboard: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val navigationEvent by viewModel.navigationEvent.collectAsState()

    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var emailCollisionMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (idToken != null) {
                    viewModel.signInWithGoogle(idToken)
                } else {
                    viewModel.clearError()
                }
            } catch (e: ApiException) {
                viewModel.clearError()
            }
        }
    }

    // Handle email collision events
    LaunchedEffect(Unit) {
        viewModel.emailCollisionEvent.collectLatest { message ->
            emailCollisionMessage = message
        }
    }

    LaunchedEffect(navigationEvent) {
        when (navigationEvent) {
            AuthNavigationEvent.NavigateToOnboarding -> {
                viewModel.clearNavigationEvent()
                onNavigateToOnboarding()
            }
            AuthNavigationEvent.NavigateToLogin -> {
                viewModel.clearNavigationEvent()
                onNavigateToLogin()
            }
            AuthNavigationEvent.NavigateToDashboard -> {
                viewModel.clearNavigationEvent()
                onNavigateToDashboard()
            }
            else -> {}
        }
    }

    // Email collision dialog
    emailCollisionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { emailCollisionMessage = null },
            title = { Text("Account Already Exists") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = {
                    emailCollisionMessage = null
                    viewModel.navigateToLogin()
                }) {
                    Text("Log In Instead")
                }
            },
            dismissButton = {
                TextButton(onClick = { emailCollisionMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Account") },
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

                Text("Create Account", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
                Text("Start managing your finances today", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = viewModel::updateEmail,
                    label = { Text("Email") },
                    placeholder = { Text("you@example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    isError = uiState.emailError != null,
                    supportingText = { if (uiState.emailError != null) Text(text = uiState.emailError ?: "", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                )

                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = viewModel::updatePassword,
                    label = { Text("Password") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    isError = uiState.passwordError != null,
                    supportingText = { if (uiState.passwordError != null) Text(text = uiState.passwordError ?: "", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                )

                OutlinedTextField(
                    value = uiState.confirmPassword,
                    onValueChange = viewModel::updateConfirmPassword,
                    label = { Text("Confirm Password") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                            Icon(
                                imageVector = if (confirmPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    isError = uiState.confirmPasswordError != null,
                    supportingText = { if (uiState.confirmPasswordError != null) Text(text = uiState.confirmPasswordError ?: "", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                )

                if (uiState.generalError != null) {
                    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(text = uiState.generalError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = viewModel::signUp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text("Sign Up")
                    }
                }

                // Divider with "or" text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        " or ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }

                // Google Sign-In button
                val webClientId = stringResource(R.string.default_web_client_id)
                GoogleSignInButton(
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
                    },
                    enabled = !uiState.isLoading
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Already have an account? ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::navigateToLogin, enabled = !uiState.isLoading) { Text("Log in") }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
