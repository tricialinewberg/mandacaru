package com.github.jvsena42.mandacaru.presentation.ui.screens.main

import androidx.annotation.DrawableRes
import com.github.jvsena42.mandacaru.R



enum class Destinations(
    val route: String,
    val label: String,
    @DrawableRes val icon: Int
) {
    NODE(route = "Node", label = "Node Info", R.drawable.ic_node),
    WALLET(route = "Wallet", label = "Wallet", R.drawable.ic_wallet),
    COINJOIN(route = "Coinjoin", label = "CoinJoin", R.drawable.ic_coinjoin),
    SETTINGS(route = "Settings", label = "Settings", R.drawable.ic_settings),
}
