package com.personal.balance.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.personal.balance.BuildConfig
import com.personal.balance.domain.ThemeMode
import com.personal.balance.viewmodel.*
import com.personal.balance.ui.screens.*

object Routes {
    const val SPLASH = "splash"
    const val SIGN_UP = "signup"
    const val LOGIN = "login"
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val DEBUG_AI = "debug_ai"
    const val FINANCIAL_ASSISTANT = "financial_assistant"
    const val ALL_TRANSACTIONS = "all_transactions"
}

@Composable
fun AppNavigation(
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit
) {
    val navController = rememberNavController()
    val startupViewModel: StartupViewModel = viewModel()

    val startupState by startupViewModel.startupState.collectAsState()

    val startRoute = when (startupState) {
        StartupState.Loading -> Routes.SPLASH
        StartupState.Unauthenticated -> Routes.SIGN_UP
        StartupState.OnboardingNeeded -> Routes.ONBOARDING
        StartupState.Ready -> Routes.DASHBOARD
    }

    NavHost(navController = navController, startDestination = startRoute) {
        composable(Routes.SPLASH) {
            SplashScreen(
                themeMode = themeMode,
                onToggleTheme = onToggleTheme
            )
        }

        composable(Routes.SIGN_UP) {
            val signUpViewModel: SignUpViewModel = viewModel()
            SignUpScreen(
                viewModel = signUpViewModel,
                themeMode = themeMode,
                onToggleTheme = onToggleTheme,
                onNavigateToLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SIGN_UP) { inclusive = true }
                    }
                },
                onNavigateToOnboarding = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.SIGN_UP) { inclusive = true }
                    }
                },
                onNavigateToDashboard = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.SIGN_UP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.LOGIN) {
            val loginViewModel: LoginViewModel = viewModel()
            LoginScreen(
                viewModel = loginViewModel,
                themeMode = themeMode,
                onToggleTheme = onToggleTheme,
                onNavigateToSignUp = {
                    navController.navigate(Routes.SIGN_UP) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToDashboard = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToOnboarding = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.ONBOARDING) {
            val onboardingViewModel: OnboardingViewModel = viewModel()
            OnboardingScreen(
                viewModel = onboardingViewModel,
                themeMode = themeMode,
                onToggleTheme = onToggleTheme,
                onNavigateToDashboard = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.DASHBOARD) {
            val dashboardViewModel: DashboardViewModel = viewModel()
            val navigationEvent by dashboardViewModel.navigationEvent.collectAsState()

            // Handle navigation events from DashboardViewModel
            navigationEvent?.let { event ->
                when (event) {
                    DashboardNavigationEvent.NavigateToAuth -> {
                        navController.navigate(Routes.SIGN_UP) {
                            popUpTo(Routes.DASHBOARD) { inclusive = true }
                        }
                        dashboardViewModel.clearNavigationEvent()
                    }
                }
            }

            DashboardScreen(
                viewModel = dashboardViewModel,
                themeMode = themeMode,
                onToggleTheme = onToggleTheme,
                onSignOut = {
                    dashboardViewModel.signOut()
                },
                onDeleteAccount = {
                    dashboardViewModel.deleteAccount()
                },
                onOpenAiSmokeTest = if (BuildConfig.DEBUG) {
                    { navController.navigate(Routes.DEBUG_AI) }
                } else {
                    null
                },
                onOpenFinancialAssistant = {
                    navController.navigate(Routes.FINANCIAL_ASSISTANT)
                },
                onOpenAllTransactions = {
                    navController.navigate(Routes.ALL_TRANSACTIONS)
                }
            )
        }

        if (BuildConfig.DEBUG) {
            composable(Routes.DEBUG_AI) {
                DebugAiSmokeScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.FINANCIAL_ASSISTANT) {
            FinancialAssistantScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ALL_TRANSACTIONS) {
            AllTransactionsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
