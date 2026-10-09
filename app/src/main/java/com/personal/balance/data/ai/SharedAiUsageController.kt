package com.personal.balance.data.ai

/**
 * The single, app-wide device-local AI usage budget shared by every AI feature
 * (Financial Assistant, Transaction Intelligence, debug AI Smoke Test). One
 * controller instance means one rolling window of allowed calls per hour across
 * the whole app — a second budget is never created.
 */
object SharedAiUsageController {
    val instance: LocalAiUsageController = LocalAiUsageController()
}