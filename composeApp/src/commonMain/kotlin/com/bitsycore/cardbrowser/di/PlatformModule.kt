package com.bitsycore.cardbrowser.di

import org.koin.core.module.Module

/**
 * What only a platform can supply.
 *
 * Three things: an `AppStorage` (a file system plus the two roots the OS wants this app to use), a
 * `LinkOpener`, and the Coil image loader that shares the app's Ktor client. Everything else in the
 * graph is common code and lives in [appModule].
 */
expect fun platformModule(): Module
