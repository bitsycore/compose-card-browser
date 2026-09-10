package com.bitsycore.cardbrowser.di

import org.koin.core.module.Module

/**
 * What only a platform can supply.
 *
 * Two things: an `AppStorage` (a file system plus the two roots the OS wants this app to use) and a
 * `LinkOpener`. Everything else in the graph is common code and lives in [appModule].
 *
 * The Coil image loader is deliberately not here and never was in the graph -- it is installed from
 * the composition by `InstallImageLoader`, because `setSingletonImageLoaderFactory` is a Compose
 * call and this module has no composition to make it from.
 */
expect fun platformModule(): Module
